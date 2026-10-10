"""Translate live transcript lines for the on-screen subtitle.

The Live session answers in audio, and its transcription comes back in
whichever language was spoken. An angler running the assistant in English
still wants to read along in Turkish, so the transcript is translated here
rather than asking the Live model for a second text modality — which would
cost another output stream per turn and slow the spoken reply down.

Only *finalised* transcript lines are sent. Interim fragments ("Bu", " küçük",
" bir soft") change several times a second and translating them would spend a
request per keystroke-sized change for text that is about to be replaced.
"""

from __future__ import annotations

import asyncio
from collections import OrderedDict
from typing import Any

from app.config import Settings, get_settings
from app.core.observability import get_logger, metrics

log = get_logger("translation")

# Translating the same line twice is common: the angler re-reads a turn, the
# app reconnects and replays, or the model repeats itself. A small LRU keeps
# those free.
_CACHE_LIMIT = 256

_PROMPT = (
    "Translate the following {source} text into {target}. It is one line of "
    "speech from a conversation between an angler and a fishing assistant, so "
    "keep fishing terminology natural for a {target} angler (species names, "
    "tackle, techniques). Reply with the translation only — no quotes, no "
    "notes, no transliteration.\n\nText:\n{text}"
)

_LANGUAGE_NAMES = {
    "en": "English",
    "tr": "Turkish",
}

#: The generateContent endpoint rejects anything under 10s outright, with
#: `400 INVALID_ARGUMENT: Manually set deadline 8s is too short`, so 10s is the
#: floor. Measured round-trips on the lite model run to about 14s when the API
#: is busy, so the bound is set above that and exists to stop a hung request
#: sitting until the CDN returns a 504 — which reads to the client as a dead
#: backend rather than a missing subtitle.
_REQUEST_TIMEOUT_MS = 30_000

_MAX_ATTEMPTS = 3

_RETRY_BACKOFF_S = 0.4

#: Substrings of transient upstream conditions, matched on the exception text
#: because the SDK raises the same error type for all of them.
_TRANSIENT_MARKERS = (
    "503",
    "429",
    "unavailable",
    "overloaded",
    "exhausted",
    "deadline exceeded",
    "timed out",
)

#: Checked first, because a rejected request can mention a transient-sounding
#: word and still be permanent. A 400 complaining that the deadline is too
#: short was retried three times before this existed, turning one bad config
#: into triple the latency on every subtitle.
_PERMANENT_MARKERS = (
    "invalid_argument",
    "permission_denied",
    "unauthenticated",
    "not_found",
    "failed_precondition",
)


def _is_transient(exc: Exception) -> bool:
    code = getattr(exc, "code", None)
    if isinstance(code, int) and 400 <= code < 500 and code != 429:
        return False
    text = str(exc).lower()
    if any(marker in text for marker in _PERMANENT_MARKERS):
        return False
    if code in (429, 503, 504):
        return True
    return any(marker in text for marker in _TRANSIENT_MARKERS)


class TranslationError(RuntimeError):
    pass


def _language_name(code: str) -> str:
    return _LANGUAGE_NAMES.get((code or "").split("-")[0].lower(), code)


class TranslationService:
    """Short-text translation over the standard text model."""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._client: Any | None = None
        self._cache: OrderedDict[tuple[str, str, str], str] = OrderedDict()

    def _get_client(self) -> Any:
        if self._client is None:
            from google import genai
            from google.genai import types

            self._client = genai.Client(
                api_key=self.settings.gemini_api_key,
                # A subtitle is worth a few seconds at most. Without a timeout
                # an overloaded upstream holds the request until the CDN gives
                # up with a 504, which reads to the client as a dead backend
                # rather than a missing translation.
                http_options=types.HttpOptions(timeout=_REQUEST_TIMEOUT_MS),
            )
        return self._client

    def _cached(self, key: tuple[str, str, str]) -> str | None:
        value = self._cache.get(key)
        if value is not None:
            self._cache.move_to_end(key)
        return value

    def _store(self, key: tuple[str, str, str], value: str) -> None:
        self._cache[key] = value
        self._cache.move_to_end(key)
        while len(self._cache) > _CACHE_LIMIT:
            self._cache.popitem(last=False)

    async def translate(self, text: str, target: str, source: str = "auto") -> str:
        text = (text or "").strip()
        if not text:
            return ""
        if not self.settings.live_api_configured:
            raise TranslationError("Gemini API key is not configured.")

        key = (text, target, source)
        cached = self._cached(key)
        if cached is not None:
            metrics.incr("translation.cache_hit")
            return cached

        from google.genai import types

        prompt = _PROMPT.format(
            source="" if source == "auto" else _language_name(source),
            target=_language_name(target),
            text=text,
        ).replace("the following  text", "the following text")

        config = types.GenerateContentConfig(
            temperature=0.0,
            # The output is always about as long as the input; a cap keeps a
            # confused model from narrating instead.
            max_output_tokens=512,
            # No `thinking_config`: the lite model rejects one outright with
            # `400 INVALID_ARGUMENT`, and there is nothing to think about in a
            # one-line translation anyway.
            #
            # There are no tools either; disabling the automatic loop skips the
            # SDK's AFC setup and its warning on every call.
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        )

        # The lite model first, then the general text model. They are
        # independently contended, and a subtitle that appears late is worth
        # much more than one that never appears because a single model was
        # briefly oversubscribed.
        models = [self.settings.gemini_translation_model]
        if self.settings.gemini_text_model not in models:
            models.append(self.settings.gemini_text_model)

        response = None
        last_error: Exception | None = None
        for model in models:
            for attempt in range(_MAX_ATTEMPTS):
                try:
                    response = await asyncio.to_thread(
                        self._get_client().models.generate_content,
                        model=model,
                        contents=prompt,
                        config=config,
                    )
                    break
                except Exception as exc:  # noqa: BLE001 - classified just below
                    last_error = exc
                    # 503/429 on a shared model is common and clears in a
                    # second. Anything else will not improve by asking again.
                    if not _is_transient(exc) or attempt == _MAX_ATTEMPTS - 1:
                        break
                    metrics.incr("translation.retry")
                    await asyncio.sleep(_RETRY_BACKOFF_S * (attempt + 1))
            if response is not None:
                break
            if last_error is not None and not _is_transient(last_error):
                # A rejected request will be rejected by the next model too.
                break
            metrics.incr("translation.model_fallback")

        if response is None:
            log.warning(
                "translation.failed",
                error_type=type(last_error).__name__ if last_error else "unknown",
            )
            metrics.incr("translation.failed")
            raise TranslationError("Translation is unavailable right now.") from last_error

        translated = (getattr(response, "text", None) or "").strip()
        if not translated:
            raise TranslationError("Translation came back empty.")

        self._store(key, translated)
        metrics.incr("translation.ok")
        return translated


_service: TranslationService | None = None


def get_translation_service() -> TranslationService:
    global _service
    if _service is None:
        _service = TranslationService()
    return _service
