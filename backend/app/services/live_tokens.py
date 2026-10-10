"""Mint short-lived, **constrained** Gemini Live credentials for the client.

Why this exists
---------------
The Android app streams audio and video straight to the Live API — proxying
that through our backend would add latency and cost for no benefit. But a
mobile app cannot hold a permanent `GEMINI_API_KEY`: APKs are trivially
unpacked. So the backend keeps the real key and hands the device an *ephemeral
token* instead (docs/SECURITY.md).

The important part is `live_connect_constraints`. An ephemeral token minted
*without* constraints is nearly as dangerous as leaking the key: the holder can
connect with any model, any system instruction and any tool set, on our quota.
Locking the token means a tampered or decompiled client gets a credential that
can only do the one thing FishMate is supposed to do.

Consequences of locking, which the client depends on:
  * the system instruction is server-controlled and cannot be overridden,
    so the safety rules in `app/resources/system_instruction.*.md` hold even
    against a modified APK;
  * the tool surface is fixed to `FUNCTION_DECLARATIONS`;
  * response modality, voice and session-management settings are fixed.

The client therefore does not invent its own setup message — it echoes the
`setup` payload returned here, injecting only a session-resumption handle on
reconnect.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from functools import lru_cache
from pathlib import Path
from typing import Any

from google import genai
from google.genai import types

from app.config import Settings, get_settings
from app.core.errors import LiveTokenError
from app.core.observability import Timer, get_logger, metrics
from app.tools.declarations import live_tools_payload

log = get_logger("live_tokens")

RESOURCES = Path(__file__).resolve().parent.parent / "resources"

#: Live API constants verified against current docs (docs/GEMINI_LIVE.md).
LIVE_WS_HOST = "generativelanguage.googleapis.com"
#: Ephemeral tokens must use the *Constrained* bidi method.
LIVE_WS_PATH_CONSTRAINED = (
    "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
)
INPUT_SAMPLE_RATE_HZ = 16_000
OUTPUT_SAMPLE_RATE_HZ = 24_000
INPUT_AUDIO_MIME = f"audio/pcm;rate={INPUT_SAMPLE_RATE_HZ}"

#: Compression keeps a long shore session alive past the documented 15-minute
#: (audio) / 2-minute (audio+video) ceilings by sliding the context window
#: instead of letting the server terminate the session.
COMPRESSION_TRIGGER_TOKENS = 16_000
COMPRESSION_TARGET_TOKENS = 8_000

#: Generation knobs we deliberately leave unset — listed so the client cannot
#: set them either (e.g. raising max_output_tokens to inflate cost, or
#: disabling our safety-relevant instruction via a competing config).
LOCKED_ADDITIONAL_FIELDS: list[str] = [
    "temperature",
    "top_p",
    "top_k",
    "max_output_tokens",
    "seed",
    # Not supported on gemini-3.8-live (the capabilities docs say to omit
    # thinkingLevel). Locking it stops a tampered client from sending it.
    "thinking_config",
]


@dataclass
class LiveCredentials:
    """What the device needs to open a Live session. Never logged."""

    token: str
    expires_at: datetime
    new_session_expires_at: datetime
    model: str
    ws_url: str
    setup: dict[str, Any]
    input_sample_rate_hz: int = INPUT_SAMPLE_RATE_HZ
    output_sample_rate_hz: int = OUTPUT_SAMPLE_RATE_HZ
    input_audio_mime: str = INPUT_AUDIO_MIME


@lru_cache(maxsize=4)
def load_system_instruction(language: str = "en") -> str:
    """Read the system instruction from its resource file.

    Kept outside code (prompt requirement) so it can be reviewed and edited
    without a redeploy of logic. Cached because it is read per token mint.
    """
    lang = "tr" if (language or "en").startswith("tr") else "en"
    path = RESOURCES / f"system_instruction.{lang}.md"
    if not path.exists():  # pragma: no cover - packaging guard
        path = RESOURCES / "system_instruction.tr.md"
    return path.read_text(encoding="utf-8").strip()


def build_live_config(
    settings: Settings,
    language: str = "en",
    voice_name: str | None = None,
    include_google_search: bool = True,
) -> dict[str, Any]:
    """The Live `setup.config` we lock into the token and echo to the client.

    Every entry below is a documented Gemini 3.8 Live setting; see
    docs/GEMINI_LIVE_IMPLEMENTATION.md for the per-field citation.

    Deliberately absent: `thinking_config` / `thinkingLevel`. The capabilities
    documentation states that for Gemini 3.8 Live thinking is "Supported
    (interleaved reasoning); thinkingLevel is not supported (omit from setup)".
    Sending it would be an obsolete-config bug, so it is also named in
    LOCKED_ADDITIONAL_FIELDS to stop a client supplying it.

    Also absent: `speech_config.language_code`. Native-audio output models
    choose the language themselves and do not support setting it explicitly.

    Also absent: `proactivity`. The capabilities documentation lists proactive
    audio as supported on Gemini 3.8 Live, but the live v1beta service rejects
    it outright::

        Unknown name "proactivity" at 'setup': Cannot find field.

    Verified against the real service on 2026-10-07. Sending it is fatal — the
    socket closes with 1007 and no session starts — so it is not sent, and not
    offered as a toggle.

    Also absent: `enable_affective_dialog`. The capabilities documentation
    lists it as supported on Gemini 3.8 Live, and the service *accepts it at
    setup* — but the first generation turn then dies with
    ``1007 Request contains an invalid argument``. That is the worst kind of
    failure: it looks configured and breaks on the first reply. Verified
    against the real service on 2026-10-07.

    Both findings contradict the published capabilities page; see
    docs/GEMINI_LIVE_IMPLEMENTATION.md for the evidence and the probe used.
    """
    config: dict[str, Any] = {
        # Native audio models support only the AUDIO response modality.
        "response_modalities": ["AUDIO"],
        "speech_config": {
            "voice_config": {
                "prebuilt_voice_config": {
                    "voice_name": voice_name or settings.gemini_voice_name,
                }
            },
        },
        "system_instruction": {"parts": [{"text": load_system_instruction(language)}]},
        "tools": live_tools_payload(
            include_google_search=include_google_search and settings.gemini_enable_google_search,
            non_blocking=settings.gemini_async_function_calling,
        ),
        # Empty object = "enable resumption and start fresh"; the server then
        # pushes sessionResumptionUpdate handles the client stores.
        "session_resumption": {},
        "context_window_compression": {
            "trigger_tokens": COMPRESSION_TRIGGER_TOKENS,
            "sliding_window": {"target_tokens": COMPRESSION_TARGET_TOKENS},
        },
        # Transcriptions drive the on-screen live transcript and let us persist
        # conversation summaries without recording audio.
        "input_audio_transcription": {},
        "output_audio_transcription": {},
        # Caps vision tokens per frame. Identifying a lure does not need HIGH,
        # and a long session on a phone battery does need LOW.
        "media_resolution": settings.gemini_media_resolution,
        "realtime_input_config": {
            "automatic_activity_detection": {
                # Server-side VAD so the user never presses a button.
                #
                # `silence_duration_ms` is the dominant term in how fast the
                # assistant feels: it is pure dead air added to every reply.
                # Too short cuts the angler off while they pause to think, too
                # long and the conversation drags — see the setting for the
                # measurements behind the default.
                "disabled": False,
                "prefix_padding_ms": settings.gemini_vad_prefix_padding_ms,
                "silence_duration_ms": settings.gemini_vad_silence_ms,
            },
            # Explicit rather than defaulted, because barge-in is a product
            # requirement: speech must interrupt the model immediately.
            "activity_handling": "START_OF_ACTIVITY_INTERRUPTS",
            # Only detected activity forms a turn, so ambient noise between
            # utterances is not folded into what the user "said".
            "turn_coverage": "TURN_INCLUDES_ONLY_ACTIVITY",
        },
    }

    return config


def build_wire_setup(
    client: Any,
    model: str,
    config: dict[str, Any],
) -> dict[str, Any]:
    """Produce the exact `setup` body the Live API expects on the wire.

    This is **not** the same shape as the SDK's `LiveConnectConfig`. The SDK
    nests everything under `config`, but the wire protocol's
    `BidiGenerateContentSetup` has no such field — it carries `model`,
    `generationConfig`, `systemInstruction`, `tools`, `realtimeInputConfig`,
    `sessionResumption`, `contextWindowCompression` and the transcription
    settings as siblings. Sending `{"setup": {"model": …, "config": {…}}}` is
    rejected outright::

        Unknown name "config" at 'setup': Cannot find field.

    Two further details the nesting hides:
      * `responseModalities`, `speechConfig` and `mediaResolution` belong
        under `generationConfig`, not at the top level.
      * `model` must carry the ``models/`` prefix.

    Rather than hand-build that mapping, this runs the config through the
    SDK's own `_LiveConnectParameters_to_mldev` converter — the same code path
    `client.aio.live.connect()` uses — so the wire format stays Google's
    responsibility and tracks their changes.
    """
    from google.genai import _common, _live_converters

    params = types.LiveConnectParameters(
        model=model,
        config=_to_live_connect_config(config),
    ).model_dump(exclude_none=True)

    request = _common.convert_to_dict(
        _live_converters._LiveConnectParameters_to_mldev(
            api_client=client._api_client,
            from_object=params,
        )
    )
    # The SDK carries `config` alongside for its own use and deletes it before
    # sending; mirror that.
    request.pop("config", None)
    request = _common.encode_unserializable_types(request)
    setup = request["setup"]
    if not isinstance(setup, dict):  # pragma: no cover - converter contract
        raise LiveTokenError("Live setup could not be built.")
    return setup


def _to_live_connect_config(config: dict[str, Any]) -> types.LiveConnectConfig:
    """Validate our dict against the SDK's typed model.

    Doing this before minting means a malformed config fails here, with a clear
    error, instead of as an opaque rejection on the client's WebSocket.
    """
    return types.LiveConnectConfig.model_validate(config)


class LiveTokenService:
    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._client: genai.Client | None = None

    @property
    def client(self) -> genai.Client:
        if self._client is None:
            if not self.settings.gemini_api_key:
                raise LiveTokenError("Gemini is not configured on the server yet.")
            # v1beta is required for ephemeral tokens with the Live API, and
            # for affective dialog and proactivity.
            self._client = genai.Client(
                api_key=self.settings.gemini_api_key,
                http_options=types.HttpOptions(api_version=self.settings.gemini_api_version),
            )
        return self._client

    def ws_url(self, token: str) -> str:
        return f"wss://{LIVE_WS_HOST}{LIVE_WS_PATH_CONSTRAINED}?access_token={token}"

    async def mint(
        self,
        *,
        language: str = "en",
        voice_name: str | None = None,
        model: str | None = None,
    ) -> LiveCredentials:
        settings = self.settings
        if not settings.live_api_configured:
            raise LiveTokenError("Gemini is not configured on the server yet.")

        model_name = model or settings.gemini_live_model
        config = build_live_config(settings, language=language, voice_name=voice_name)

        now = datetime.now(UTC)
        expire_at = now + timedelta(minutes=settings.ephemeral_token_expire_minutes)
        new_session_expire_at = now + timedelta(
            seconds=settings.ephemeral_token_new_session_expire_seconds
        )

        try:
            typed_config = _to_live_connect_config(config)
        except Exception as exc:  # pragma: no cover - config bug, not runtime
            log.error("live_token.config_invalid", error=str(exc))
            raise LiveTokenError("Live configuration is invalid on the server.") from exc

        try:
            with Timer("live_token.mint_latency"):
                # The SDK call is sync/blocking; keep the event loop free.
                token_obj = await asyncio.to_thread(
                    self.client.auth_tokens.create,
                    config=types.CreateAuthTokenConfig(
                        uses=settings.ephemeral_token_uses,
                        expire_time=expire_at,
                        new_session_expire_time=new_session_expire_at,
                        # THE security control — see module docstring.
                        live_connect_constraints=types.LiveConnectConstraints(
                            model=model_name,
                            config=typed_config,
                        ),
                        lock_additional_fields=LOCKED_ADDITIONAL_FIELDS,
                    ),
                )
        except Exception as exc:
            metrics.incr("live_token.mint_failed")
            # Provider messages can contain the API key; never propagate them.
            log.error("live_token.mint_failed", error_type=type(exc).__name__)
            raise LiveTokenError() from exc

        token_value = getattr(token_obj, "name", None)
        if not token_value:
            metrics.incr("live_token.mint_failed")
            raise LiveTokenError()

        metrics.incr("live_token.minted")
        log.info(
            "live_token.minted",
            model=model_name,
            language=language,
            uses=settings.ephemeral_token_uses,
            expires_in_s=int((expire_at - now).total_seconds()),
            locked_fields=len(LOCKED_ADDITIONAL_FIELDS),
            tools=len(config["tools"]),
        )
        return LiveCredentials(
            token=token_value,
            expires_at=expire_at,
            new_session_expires_at=new_session_expire_at,
            model=model_name,
            ws_url=self.ws_url(token_value),
            # The complete wire `setup` body. The client sends it verbatim
            # as {"setup": <this>}, injecting only a resumption handle.
            setup=build_wire_setup(self.client, model_name, config),
        )


_service: LiveTokenService | None = None


def get_live_token_service(settings: Settings | None = None) -> LiveTokenService:
    global _service
    if _service is None:
        _service = LiveTokenService(settings)
    return _service


def set_live_token_service(service: LiveTokenService | None) -> None:
    """Override for tests / Demo Mode."""
    global _service
    _service = service
