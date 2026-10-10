"""Subtitle translation.

The transcript subtitle is cosmetic, so the rules that matter are about
cost and failure: identical lines must not be re-billed, and a translation
outage must not read to the client as a broken conversation.
"""

from __future__ import annotations

import pytest

from app.config import Settings
from app.services.translation import TranslationError, TranslationService


class StubModels:
    def __init__(self, text: str = "su bugün çok berrak") -> None:
        self.text = text
        self.calls: list[dict] = []

    def generate_content(self, **kwargs):  # noqa: ANN003 - mirrors the SDK
        self.calls.append(kwargs)
        return type("Response", (), {"text": self.text})()


class StubClient:
    def __init__(self, text: str = "su bugün çok berrak") -> None:
        self.models = StubModels(text)


def _service(settings, text: str = "su bugün çok berrak") -> tuple[TranslationService, StubClient]:
    service = TranslationService(settings)
    client = StubClient(text)
    service._client = client
    return service, client


def _settings(**over) -> Settings:
    base = {"auth_secret": "s" * 48, "gemini_api_key": "test-key"}
    base.update(over)
    return Settings(**base)


@pytest.fixture
def configured_settings() -> Settings:
    return _settings()


async def test_translates_a_transcript_line(configured_settings):
    service, client = _service(configured_settings)

    result = await service.translate("the water is very clear today", target="tr")

    assert result == "su bugün çok berrak"
    assert len(client.models.calls) == 1
    prompt = client.models.calls[0]["contents"]
    # The target language has to be named, or the model guesses from the input.
    assert "Turkish" in prompt
    assert "the water is very clear today" in prompt


async def test_repeated_lines_are_served_from_cache(configured_settings):
    # The assistant repeats stock phrases many times in a session, and a
    # reconnect replays recent transcript. Each repeat must be free.
    service, client = _service(configured_settings)

    await service.translate("tight lines", target="tr")
    await service.translate("tight lines", target="tr")

    assert len(client.models.calls) == 1


async def test_same_line_in_another_language_is_a_separate_request(configured_settings):
    service, client = _service(configured_settings)

    await service.translate("tight lines", target="tr")
    await service.translate("tight lines", target="en")

    assert len(client.models.calls) == 2


async def test_blank_input_costs_nothing(configured_settings):
    service, client = _service(configured_settings)

    assert await service.translate("   ", target="tr") == ""
    assert client.models.calls == []


async def test_missing_api_key_is_an_error_not_a_silent_empty_string():
    service = TranslationService(_settings(gemini_api_key=""))

    with pytest.raises(TranslationError):
        await service.translate("tight lines", target="tr")


async def test_model_failure_surfaces_as_translation_error(configured_settings):
    service, client = _service(configured_settings)

    def boom(**kwargs):  # noqa: ANN003
        raise RuntimeError("upstream unavailable")

    client.models.generate_content = boom

    with pytest.raises(TranslationError):
        await service.translate("tight lines", target="tr")


async def test_empty_model_output_is_rejected(configured_settings):
    # An empty subtitle cached forever would be worse than no subtitle.
    service, _ = _service(configured_settings, text="")

    with pytest.raises(TranslationError):
        await service.translate("tight lines", target="tr")


async def test_transient_upstream_error_is_retried(configured_settings):
    # Observed in production: the shared text model answered 503 and the
    # subtitle vanished, even though the next attempt a second later worked.
    service, client = _service(configured_settings)
    attempts = {"n": 0}

    def flaky(**kwargs):  # noqa: ANN003
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise RuntimeError("503 Service Unavailable: model is overloaded")
        return type("Response", (), {"text": "su berrak"})()

    client.models.generate_content = flaky

    assert await service.translate("the water is clear", target="tr") == "su berrak"
    assert attempts["n"] == 2


async def test_a_permanent_error_is_not_retried(configured_settings):
    # Retrying a rejected request just multiplies the latency of a failure
    # the caller is going to see anyway.
    service, client = _service(configured_settings)
    attempts = {"n": 0}

    def rejected(**kwargs):  # noqa: ANN003
        attempts["n"] += 1
        raise RuntimeError("400 INVALID_ARGUMENT: bad request")

    client.models.generate_content = rejected

    with pytest.raises(TranslationError):
        await service.translate("the water is clear", target="tr")
    assert attempts["n"] == 1


async def test_a_rejection_mentioning_a_deadline_is_not_retried(configured_settings):
    # The exact shipped bug: an 8s timeout was below the endpoint's 10s
    # minimum, and because the rejection said "deadline" the retry logic
    # treated a permanent 400 as transient and tripled the latency.
    service, client = _service(configured_settings)
    attempts = {"n": 0}

    def rejected(**kwargs):  # noqa: ANN003
        attempts["n"] += 1
        raise RuntimeError(
            "400 INVALID_ARGUMENT. Manually set deadline 8s is too short. "
            "Minimum allowed deadline is 10s."
        )

    client.models.generate_content = rejected

    with pytest.raises(TranslationError):
        await service.translate("the water is clear", target="tr")
    assert attempts["n"] == 1


async def test_falls_back_to_the_general_model(configured_settings):
    # The lite model and the general one are contended independently, and the
    # general flash model was returning 503 then timing out while lite
    # answered every time. Either one being busy must not lose the subtitle.
    service, client = _service(configured_settings)
    tried: list[str] = []

    def lite_is_overloaded(**kwargs):  # noqa: ANN003
        tried.append(kwargs["model"])
        if kwargs["model"] == configured_settings.gemini_translation_model:
            raise RuntimeError("503 This model is currently experiencing high demand")
        return type("Response", (), {"text": "su berrak"})()

    client.models.generate_content = lite_is_overloaded

    assert await service.translate("the water is clear", target="tr") == "su berrak"
    assert tried[0] == configured_settings.gemini_translation_model
    assert tried[-1] == configured_settings.gemini_text_model


async def test_a_rejection_does_not_cascade_to_the_fallback_model(configured_settings):
    # A malformed request will be malformed for the next model too; trying it
    # again only doubles the latency of a failure the caller already has.
    service, client = _service(configured_settings)
    tried: list[str] = []

    def rejected(**kwargs):  # noqa: ANN003
        tried.append(kwargs["model"])
        raise RuntimeError("400 INVALID_ARGUMENT: Request contains an invalid argument")

    client.models.generate_content = rejected

    with pytest.raises(TranslationError):
        await service.translate("the water is clear", target="tr")
    assert tried == [configured_settings.gemini_translation_model]


async def test_retries_are_bounded(configured_settings):
    service, client = _service(configured_settings)
    attempts = {"n": 0}

    def always_overloaded(**kwargs):  # noqa: ANN003
        attempts["n"] += 1
        raise RuntimeError("503 unavailable")

    client.models.generate_content = always_overloaded

    with pytest.raises(TranslationError):
        await service.translate("the water is clear", target="tr")
    # Three attempts on each of the two models, and no more: a subtitle must
    # not hold a request open until the CDN times out.
    assert attempts["n"] == 6


async def test_endpoint_requires_authentication(client):
    response = await client.post(
        "/api/v1/live/translate", json={"text": "tight lines", "target_language": "tr"}
    )
    assert response.status_code in (401, 403)


async def test_endpoint_returns_the_translation(client, auth_headers, monkeypatch):
    from app.services import translation as translation_module

    service = TranslationService()

    async def fake_translate(text: str, target: str, source: str = "auto") -> str:
        return f"[{target}] {text}"

    service.translate = fake_translate  # type: ignore[assignment]
    monkeypatch.setattr(translation_module, "_service", service)

    response = await client.post(
        "/api/v1/live/translate",
        json={"text": "the water is clear", "target_language": "tr"},
        headers=auth_headers,
    )

    assert response.status_code == 200
    assert response.json() == {"text": "[tr] the water is clear", "target_language": "tr"}


async def test_endpoint_reports_an_outage_as_bad_gateway(client, auth_headers, monkeypatch):
    from app.services import translation as translation_module

    service = TranslationService()

    async def fake_translate(text: str, target: str, source: str = "auto") -> str:
        raise TranslationError("Translation is unavailable right now.")

    service.translate = fake_translate  # type: ignore[assignment]
    monkeypatch.setattr(translation_module, "_service", service)

    response = await client.post(
        "/api/v1/live/translate",
        json={"text": "the water is clear", "target_language": "tr"},
        headers=auth_headers,
    )

    assert response.status_code == 502
