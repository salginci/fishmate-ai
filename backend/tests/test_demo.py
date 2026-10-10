"""Demo Mode: deterministic, production-path, and refused in production."""

from __future__ import annotations

from app.config import Settings, get_settings


class TestScenarios:
    async def test_scenarios_cover_every_demonstrated_capability(self, client, auth_headers):
        response = await client.get("/api/v1/demo/scenarios", headers=auth_headers)
        assert response.status_code == 200
        body = response.json()
        capabilities = " ".join(s["capability"] for s in body["scenarios"]).lower()
        for required in (
            "audio",
            "video",
            "function calling",
            "vision",
            "retrieval",
        ):
            assert required in capabilities, required
        assert body["fallback"]
        assert body["spot"]["name"] == "Bostanlı"

    async def test_every_scenario_has_a_line_to_say_and_an_expectation(self, client, auth_headers):
        body = (await client.get("/api/v1/demo/scenarios", headers=auth_headers)).json()
        for scenario in body["scenarios"]:
            assert scenario["say"], scenario
            assert scenario["expect"], scenario

    async def test_includes_the_regulation_refusal_scenario(self, client, auth_headers):
        """The honest-refusal behaviour is worth demonstrating deliberately."""
        body = (await client.get("/api/v1/demo/scenarios", headers=auth_headers)).json()
        refusal = [s for s in body["scenarios"] if "refusal" in s["capability"].lower()]
        assert refusal
        assert "never an invented number" in refusal[0]["expect"].lower()

    async def test_requires_auth(self, client):
        assert (await client.get("/api/v1/demo/scenarios")).status_code == 401


class TestSeeding:
    async def test_seed_creates_retrievable_history(self, client, auth_headers):
        response = await client.post("/api/v1/demo/seed", headers=auth_headers)
        assert response.status_code == 200
        body = response.json()
        assert body["sessions_created"] == 6
        assert body["catches_created"] == 8
        assert body["tackle_created"] > 5

    async def test_seeded_history_answers_scenario_six(self, client, auth_headers):
        """'What did I use last time for levrek?' must resolve from real rows."""
        await client.post("/api/v1/demo/seed", headers=auth_headers)
        response = await client.post(
            "/api/v1/tools/execute",
            headers=auth_headers,
            json={"name": "get_recent_catches", "args": {"species": "levrek"}},
        )
        payload = response.json()["response"]
        assert payload["total_matching_catches"] == 4
        assert payload["lures_that_produced"][0]["lure"] == "Minnow 11 cm / 11 g"
        assert payload["lures_that_produced"][0]["catches"] == 3
        # Enough samples that the pattern claim is legitimate.
        assert "instruction_to_model" not in payload or "too few" not in payload.get(
            "instruction_to_model", ""
        )

    async def test_seeded_sessions_have_reports(self, client, auth_headers):
        await client.post("/api/v1/demo/seed", headers=auth_headers)
        sessions = (await client.get("/api/v1/sessions", headers=auth_headers)).json()
        assert all(s["status"] == "completed" for s in sessions)
        assert all(s["summary"] for s in sessions)

    async def test_seeded_tackle_enables_recommendation(self, client, auth_headers):
        await client.post("/api/v1/demo/seed", headers=auth_headers)
        response = await client.post(
            "/api/v1/tools/execute",
            headers=auth_headers,
            json={
                "name": "recommend_setup",
                "args": {
                    "target_species": "levrek",
                    "latitude": 38.46,
                    "longitude": 27.09,
                },
            },
        )
        payload = response.json()["response"]
        assert "lure" in payload, payload
        assert "missing_tackle" not in payload

    async def test_seed_is_idempotent_for_tackle(self, client, auth_headers):
        first = (await client.post("/api/v1/demo/seed", headers=auth_headers)).json()
        second = (await client.post("/api/v1/demo/seed", headers=auth_headers)).json()
        assert first["tackle_created"] > 0
        assert second["tackle_created"] == 0

    async def test_reset_clears_previous_history(self, client, auth_headers):
        await client.post("/api/v1/demo/seed", headers=auth_headers)
        await client.post("/api/v1/demo/seed?reset=true", headers=auth_headers)
        catches = (await client.get("/api/v1/catches", headers=auth_headers)).json()
        assert len(catches) == 8  # not 16

    async def test_mock_weather_toggle_is_deterministic(self, client, auth_headers):
        response = await client.post("/api/v1/demo/mock-weather", headers=auth_headers)
        assert response.json()["weather_provider"] == "mock"
        first = await client.get(
            "/api/v1/conditions?latitude=38.46&longitude=27.09", headers=auth_headers
        )
        second = await client.get(
            "/api/v1/conditions?latitude=38.46&longitude=27.09", headers=auth_headers
        )
        assert first.json()["summary"] == second.json()["summary"]


class TestProductionGuard:
    def test_demo_routes_absent_in_production(self):
        """The seeding endpoint must not exist on a production deployment."""
        import app.main as main_module

        def production_settings():
            return Settings(
                auth_secret="p" * 48,
                gemini_api_key="k",
                environment="production",
                demo_mode_enabled=False,
                cors_origins="https://fishmate.example",
            )

        # `app.main` binds get_settings at import time, so patch it there.
        original = main_module.get_settings
        main_module.get_settings = production_settings
        try:
            spec = main_module.create_app().openapi()
            assert not any(path.startswith("/api/v1/demo") for path in spec["paths"])
        finally:
            main_module.get_settings = original
            get_settings.cache_clear()

    def test_production_refuses_bad_configuration(self):
        settings = Settings(
            auth_secret="",
            environment="production",
            cors_origins="*",
            demo_mode_enabled=True,
        )
        problems = settings.startup_problems()
        assert any("AUTH_SECRET" in p for p in problems)
        assert any("CORS" in p for p in problems)
        assert any("DEMO_MODE" in p for p in problems)

    def test_healthy_production_config_has_no_problems(self):
        settings = Settings(
            auth_secret="p" * 48,
            gemini_api_key="key",
            environment="production",
            cors_origins="https://fishmate.example",
            demo_mode_enabled=False,
        )
        assert settings.startup_problems() == []
