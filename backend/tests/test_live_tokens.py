"""Ephemeral-token security.

These are the highest-value tests in the backend. If the minted token is not
constrained, a decompiled APK can connect to Gemini with any model, any system
instruction and any tool set, billed to us — the documented failure mode for
unconstrained ephemeral tokens.
"""

from __future__ import annotations

from datetime import UTC, datetime
from unittest.mock import MagicMock

import pytest

from app.config import Settings
from app.core.errors import LiveTokenError
from app.services.live_tokens import (
    COMPRESSION_TARGET_TOKENS,
    COMPRESSION_TRIGGER_TOKENS,
    LIVE_WS_PATH_CONSTRAINED,
    LOCKED_ADDITIONAL_FIELDS,
    LiveTokenService,
    build_live_config,
    build_wire_setup,
    load_system_instruction,
)
from app.tools.declarations import TOOL_NAMES


def _wire(settings: Settings) -> dict:
    """The real wire `setup` body, via the SDK converter (needs a client)."""
    from google import genai
    from google.genai import types as gt

    client = genai.Client(
        api_key=settings.gemini_api_key or "test-key",
        http_options=gt.HttpOptions(api_version=settings.gemini_api_version),
    )
    return build_wire_setup(client, settings.gemini_live_model, build_live_config(settings))


def _settings(**over) -> Settings:
    base = {"auth_secret": "s" * 48, "gemini_api_key": "test-key"}
    base.update(over)
    return Settings(**base)


class TestLiveConfig:
    def test_locks_audio_response_modality(self):
        config = build_live_config(_settings())
        assert config["response_modalities"] == ["AUDIO"]

    def test_declares_every_tool(self):
        tools = build_live_config(_settings())["tools"]
        declared = {d["name"] for d in tools[0]["function_declarations"]}
        assert declared == set(TOOL_NAMES)

    def test_google_search_added_only_when_enabled(self):
        # Off by default — this key's quota rejects it at setup (see
        # TestWireFormatIsSdkDerived::test_google_search_is_off_by_default).
        assert {"google_search": {}} not in build_live_config(_settings())["tools"]
        assert {"google_search": {}} in build_live_config(
            _settings(gemini_enable_google_search=True)
        )["tools"]

    def test_session_resumption_enabled(self):
        """Required: the connection is dropped roughly every 10 minutes."""
        assert build_live_config(_settings())["session_resumption"] == {}

    def test_context_compression_enabled(self):
        """Without it, audio+video sessions die at the 2-minute ceiling."""
        compression = build_live_config(_settings())["context_window_compression"]
        assert compression["trigger_tokens"] == COMPRESSION_TRIGGER_TOKENS
        assert compression["sliding_window"]["target_tokens"] == COMPRESSION_TARGET_TOKENS
        assert compression["sliding_window"]["target_tokens"] < compression["trigger_tokens"]

    def test_transcriptions_enabled_both_directions(self):
        config = build_live_config(_settings())
        assert config["input_audio_transcription"] == {}
        assert config["output_audio_transcription"] == {}

    def test_automatic_vad_enabled_for_handsfree(self):
        vad = build_live_config(_settings())["realtime_input_config"][
            "automatic_activity_detection"
        ]
        assert vad["disabled"] is False
        # A short silence window cuts the user off mid-sentence in wind.
        assert vad["silence_duration_ms"] >= 500

    def test_system_instruction_is_embedded_not_client_supplied(self):
        config = build_live_config(_settings(), language="tr")
        text = config["system_instruction"]["parts"][0]["text"]
        assert len(text) > 500
        assert "GÖZLEM" in text  # the observation/inference/recommendation rule

    def test_language_selects_instruction_resource(self):
        tr = build_live_config(_settings(), language="tr")["system_instruction"]["parts"][0]["text"]
        en = build_live_config(_settings(), language="en")["system_instruction"]["parts"][0]["text"]
        assert tr != en
        assert "ROLE" in en

    def test_voice_override_applies(self):
        config = build_live_config(_settings(), voice_name="Puck")
        voice = config["speech_config"]["voice_config"]["prebuilt_voice_config"]["voice_name"]
        assert voice == "Puck"

    def test_config_validates_against_the_real_sdk_types(self):
        """Guards against drift between our dict and the current API shape."""
        from google.genai import types

        typed = types.LiveConnectConfig.model_validate(build_live_config(_settings()))
        assert typed.response_modalities
        assert typed.session_resumption is not None
        # Function declarations only; Search is opt-in (see above).
        assert len(typed.tools) == 1


class TestSystemInstruction:
    def test_forbids_inventing_regulations(self):
        for language in ("tr", "en"):
            text = load_system_instruction(language).lower()
            assert "uydurma" in text or "never invent" in text

    def test_unknown_language_falls_back(self):
        assert load_system_instruction("de")


class TestMinting:
    async def test_requires_api_key(self):
        service = LiveTokenService(_settings(gemini_api_key=""))
        with pytest.raises(LiveTokenError):
            await service.mint()

    async def test_mint_locks_model_config_and_extra_fields(self):
        """The core security assertion."""
        settings = _settings()
        service = LiveTokenService(settings)
        captured = {}

        def fake_create(config):
            captured["config"] = config
            token = MagicMock()
            token.name = "ephemeral-abc123"
            return token

        fake_client = MagicMock()
        fake_client.auth_tokens.create = fake_create
        service._client = fake_client

        credentials = await service.mint(language="tr")

        sent = captured["config"]
        # 1. Constraints present at all.
        assert sent.live_connect_constraints is not None
        # 2. Model pinned.
        assert sent.live_connect_constraints.model == settings.gemini_live_model
        # 3. Config pinned, including the system instruction and tools.
        locked = sent.live_connect_constraints.config
        assert locked.response_modalities
        assert len(locked.tools) == 1
        assert locked.system_instruction is not None
        # 4. Generation knobs the client must not be able to set.
        assert set(sent.lock_additional_fields) == set(LOCKED_ADDITIONAL_FIELDS)
        # 5. Single-use, short-lived.
        assert sent.uses == settings.ephemeral_token_uses
        assert sent.expire_time > datetime.now(UTC)
        assert sent.new_session_expire_time < sent.expire_time
        # 6. The constrained endpoint, with the token as access_token.
        assert LIVE_WS_PATH_CONSTRAINED in credentials.ws_url
        assert "access_token=ephemeral-abc123" in credentials.ws_url

    async def test_audio_rates_match_the_live_api_spec(self):
        service = LiveTokenService(_settings())
        fake = MagicMock()
        fake.auth_tokens.create = lambda config: MagicMock(name_="x", **{"name": "tok"})
        service._client = fake
        credentials = await service.mint()
        assert credentials.input_sample_rate_hz == 16_000
        assert credentials.output_sample_rate_hz == 24_000
        assert credentials.input_audio_mime == "audio/pcm;rate=16000"

    async def test_provider_failure_becomes_user_safe_error(self):
        """A provider message can contain the API key — it must not propagate."""
        service = LiveTokenService(_settings())
        fake = MagicMock()

        def boom(config):
            raise RuntimeError("401 from https://...?key=AIzaSECRETKEY")

        fake.auth_tokens.create = boom
        service._client = fake

        with pytest.raises(LiveTokenError) as exc:
            await service.mint()
        assert "AIzaSECRETKEY" not in str(exc.value)
        assert "Gemini" in exc.value.message or "connection" in exc.value.message

    async def test_missing_token_name_is_an_error(self):
        service = LiveTokenService(_settings())
        fake = MagicMock()
        empty = MagicMock()
        empty.name = None
        fake.auth_tokens.create = lambda config: empty
        service._client = fake
        with pytest.raises(LiveTokenError):
            await service.mint()


class TestLiveSessionEndpoint:
    async def test_requires_authentication(self, client):
        assert (await client.post("/api/v1/live/session", json={})).status_code == 401

    async def test_returns_setup_the_client_must_echo(self, client, auth_headers):
        response = await client.post(
            "/api/v1/live/session", headers=auth_headers, json={"expects_video": True}
        )
        assert response.status_code == 200
        body = response.json()
        assert body["token"]
        assert body["ws_url"].startswith("wss://")
        assert body["input_sample_rate_hz"] == 16_000
        assert body["output_sample_rate_hz"] == 24_000
        # The config is server-chosen, not client-chosen, and already in
        # wire (camelCase) form so the client can send it verbatim.
        config = body["setup"]
        assert config["generationConfig"]["responseModalities"] == ["AUDIO"]
        assert config["sessionResumption"] == {}
        assert "contextWindowCompression" in config

    async def test_never_returns_the_real_api_key(self, client, auth_headers):
        response = await client.post("/api/v1/live/session", headers=auth_headers, json={})
        assert "test-key-not-real" not in response.text

    async def test_includes_small_user_brief(self, client, auth_headers):
        body = (await client.post("/api/v1/live/session", headers=auth_headers, json={})).json()
        brief = body["user_brief"]
        assert brief["display_name"] == "Serkan"
        assert brief["total_catches"] == 0
        # Must stay tiny — it is injected into the model's context.
        assert len(str(brief)) < 2000

    async def test_a_sent_position_comes_back_as_a_place_name(self, client, auth_headers):
        """So the session can open with "you're at Bostanlı" instead of asking."""
        body = (
            await client.post(
                "/api/v1/live/session",
                headers=auth_headers,
                json={"latitude": 38.4570, "longitude": 27.0984},
            )
        ).json()
        brief = body["user_brief"]
        assert "Bostanlı" in brief["place"]["name"]
        assert brief["place"]["region"] == "İzmir"
        assert brief["coordinates"]["latitude"] is not None

    async def test_the_position_is_coarsened_to_the_consented_precision(self, client, auth_headers):
        """`approximate` is the default, and it is a promise, not a hint."""
        body = (
            await client.post(
                "/api/v1/live/session",
                headers=auth_headers,
                json={"latitude": 38.456789, "longitude": 27.098765},
            )
        ).json()
        coords = body["user_brief"]["coordinates"]
        # ~1 km: a neighbourhood, not the rock they are standing on.
        assert coords["latitude"] == 38.46
        assert coords["longitude"] == 27.1

    async def test_no_position_sent_means_no_place(self, client, auth_headers):
        body = (await client.post("/api/v1/live/session", headers=auth_headers, json={})).json()
        brief = body["user_brief"]
        assert "place" not in brief
        assert "coordinates" not in brief

    async def test_a_nonsense_position_is_rejected_rather_than_geocoded(self, client, auth_headers):
        response = await client.post(
            "/api/v1/live/session",
            headers=auth_headers,
            json={"latitude": 999, "longitude": 27.0},
        )
        assert response.status_code == 422

    async def test_uses_the_users_language_preference(
        self, client, auth_headers, fake_live_service
    ):
        await client.post("/api/v1/live/session", headers=auth_headers, json={})
        assert fake_live_service.calls[-1]["language"] == "tr"


class TestWireSetupShape:
    """The `setup` body must match the wire protocol, not the SDK's object model.

    `BidiGenerateContentSetup` has no `config` field: `model`,
    `generationConfig`, `systemInstruction`, `tools`, `realtimeInputConfig`,
    `sessionResumption` and `contextWindowCompression` are siblings. Sending
    the SDK's nesting is rejected outright with
    `Unknown name "config" at 'setup'` — verified against the live service.
    """

    def test_has_no_config_wrapper(self):
        setup = _wire(_settings())
        assert "config" not in setup

    def test_model_carries_the_models_prefix(self):
        assert _wire(_settings())["model"] == "models/gemini-3.8-live"

    def test_generation_settings_live_under_generation_config(self):
        gen = _wire(_settings())["generationConfig"]
        assert gen["responseModalities"] == ["AUDIO"]
        assert gen["mediaResolution"] == "MEDIA_RESOLUTION_LOW"
        assert "Kore" in str(gen["speechConfig"])

    def test_session_fields_are_top_level(self):
        setup = _wire(_settings())
        for key in (
            "systemInstruction",
            "tools",
            "realtimeInputConfig",
            "sessionResumption",
            "contextWindowCompression",
            "inputAudioTranscription",
            "outputAudioTranscription",
        ):
            assert key in setup, key

    def test_matches_the_sdk_converter_exactly(self):
        """The wire format is Google's responsibility, not ours.

        The Live API rejects or ignores malformed setup, so this pins our
        output to the same converter `client.aio.live.connect()` uses.
        """
        from google import genai
        from google.genai import _common, _live_converters, types

        settings = _settings()
        client = genai.Client(
            api_key="test-key",
            http_options=types.HttpOptions(api_version=settings.gemini_api_version),
        )
        params = types.LiveConnectParameters(
            model=settings.gemini_live_model,
            config=types.LiveConnectConfig.model_validate(build_live_config(settings)),
        ).model_dump(exclude_none=True)
        expected = _common.convert_to_dict(
            _live_converters._LiveConnectParameters_to_mldev(
                api_client=client._api_client, from_object=params
            )
        )
        expected.pop("config", None)
        expected = _common.encode_unserializable_types(expected)
        assert _wire(settings) == expected["setup"]

    def test_tool_argument_names_are_not_mangled(self):
        """Decisive: these names are matched against our Pydantic models when
        the call comes back, so camel-casing them breaks every tool call."""
        declarations = _wire(_settings())["tools"][0]["functionDeclarations"]
        by_name = {d["name"]: d for d in declarations}
        log_catch = by_name["log_catch"]["parameters"]["properties"]
        assert "ai_confidence" in log_catch
        assert "length_cm" in log_catch
        assert "target_species" in by_name["recommend_setup"]["parameters"]["properties"]
        assert "start_fishing_session" in by_name

    def test_every_declared_arg_matches_a_pydantic_field(self):
        """Catches drift between a declaration and its validation model."""
        from app.tools.declarations import DEVICE_TOOLS
        from app.tools.registry import get_tool_registry

        registry = get_tool_registry()
        for declaration in _wire(_settings())["tools"][0]["functionDeclarations"]:
            if declaration["name"] in DEVICE_TOOLS:
                # Executed on the device, so there is no Pydantic model here to
                # drift from. The client validates these arguments itself; see
                # `executeDeviceTool` in both FishingModeControllers.
                continue
            spec = registry.get(declaration["name"])
            assert spec is not None, declaration["name"]
            accepted = set(spec.args_model.model_fields) | {
                f.alias for f in spec.args_model.model_fields.values() if f.alias
            }
            declared = set((declaration.get("parameters") or {}).get("properties") or {})
            assert not declared - accepted, declaration["name"]


class TestGemini38LiveCompliance:
    """Settings verified against the live service on 2026-10-07.

    Two of these contradict the published capabilities page, which is exactly
    why they are pinned here.
    """

    def test_thinking_config_is_never_sent(self):
        """Docs: for Gemini 3.8 Live, `thinkingLevel` "is not supported (omit
        from setup)"."""
        assert "thinking_config" not in build_live_config(_settings())
        setup = _wire(_settings())
        assert "thinkingConfig" not in setup
        assert "thinkingConfig" not in setup.get("generationConfig", {})

    def test_thinking_config_is_locked_against_the_client(self):
        assert "thinking_config" in LOCKED_ADDITIONAL_FIELDS

    def test_language_code_is_not_set(self):
        """Native-audio models choose the language themselves."""
        assert "language_code" not in str(build_live_config(_settings())["speech_config"])

    def test_proactivity_is_never_sent(self):
        """Listed as supported on 3.8 Live, but the live v1beta service rejects
        it at setup: `Unknown name "proactivity"` and closes with 1007."""
        assert "proactivity" not in _wire(_settings())

    def test_affective_dialog_is_never_sent(self):
        """Also listed as supported, and accepted at *setup* — but the first
        generation turn then dies with `1007 invalid argument`. The worst
        failure mode: looks configured, breaks on the first reply."""
        setup = _wire(_settings())
        assert "enableAffectiveDialog" not in str(setup)

    def test_audio_only_response_modality(self):
        assert _wire(_settings())["generationConfig"]["responseModalities"] == ["AUDIO"]

    def test_barge_in_and_turn_coverage_are_explicit(self):
        """Note the key casing: the SDK's converter emits top-level setup keys
        in camelCase but leaves *nested* ones snake_case. Proto3 JSON accepts
        both, and this is byte-for-byte what the SDK itself sends — verified
        working against the live service."""
        realtime = _wire(_settings())["realtimeInputConfig"]
        assert realtime["activity_handling"] == "START_OF_ACTIVITY_INTERRUPTS"
        assert realtime["turn_coverage"] == "TURN_INCLUDES_ONLY_ACTIVITY"
        assert realtime["automatic_activity_detection"]["silence_duration_ms"] >= 500

    def test_compression_and_resumption_enabled(self):
        """Without these a session dies at the 2-minute audio+video ceiling."""
        setup = _wire(_settings())
        assert setup["contextWindowCompression"]["sliding_window"]["target_tokens"] > 0
        assert "sessionResumption" in setup


class TestGoogleSearchGating:
    def test_off_by_default(self):
        """Search grounding needs its own quota; a key without it is rejected
        at setup with `1011 ... exceeded your current quota`, killing the whole
        session. Verified 3/3 attempts. Off by default is the fail-safe."""
        tools = _wire(_settings())["tools"]
        assert all("googleSearch" not in t for t in tools)

    def test_uses_the_builtin_tool_when_enabled(self):
        tools = _wire(_settings(gemini_enable_google_search=True))["tools"]
        assert {"googleSearch": {}} in tools


class TestAsyncFunctionCalling:
    def test_disabled_by_default(self):
        from app.config import Settings

        assert Settings(auth_secret="x" * 48).gemini_async_function_calling is False
        declarations = _wire(_settings())["tools"][0]["functionDeclarations"]
        assert all("behavior" not in d for d in declarations)

    def test_enabled_only_for_slow_tools(self):
        from app.tools.declarations import SLOW_TOOLS

        declarations = _wire(_settings(gemini_async_function_calling=True))["tools"][0][
            "functionDeclarations"
        ]
        non_blocking = {d["name"] for d in declarations if d.get("behavior") == "NON_BLOCKING"}
        assert non_blocking == set(SLOW_TOOLS)


class TestVoiceActivityDetection:
    """VAD timing is the dominant term in how fast the assistant feels.

    Note the key casing: the SDK converter renames the fields it knows, but
    passes `realtime_input_config` through as an opaque dict, so everything
    inside it stays snake_case on the wire.
    """

    def test_silence_window_is_configurable(self):
        # It was hard-coded at 900ms, which is pure dead air on every reply:
        # the model itself needs about 1.3s to start answering, so the pair
        # pushed a spoken reply past two seconds.
        wire = _wire(_settings(gemini_vad_silence_ms=400))
        vad = wire["realtimeInputConfig"]["automatic_activity_detection"]
        assert vad["silence_duration_ms"] == 400

    def test_prefix_padding_is_configurable(self):
        wire = _wire(_settings(gemini_vad_prefix_padding_ms=250))
        vad = wire["realtimeInputConfig"]["automatic_activity_detection"]
        assert vad["prefix_padding_ms"] == 250

    def test_default_silence_window_stays_responsive(self):
        # A regression here is invisible in tests and obvious to a user, so
        # the default is pinned rather than left to drift.
        vad = _wire(_settings())["realtimeInputConfig"]["automatic_activity_detection"]
        assert vad["silence_duration_ms"] <= 600
        assert vad["disabled"] is False

    def test_barge_in_remains_enabled(self):
        # Hands-free is the product; speech has to interrupt the model.
        config = _wire(_settings())["realtimeInputConfig"]
        assert config["activity_handling"] == "START_OF_ACTIVITY_INTERRUPTS"
