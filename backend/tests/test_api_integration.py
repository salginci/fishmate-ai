"""End-to-end API behaviour: tackle, sessions, catches, home, ownership."""

from __future__ import annotations

import pytest


@pytest.fixture
async def other_headers(client) -> dict:
    response = await client.post(
        "/api/v1/auth/register",
        json={
            "email": "intruder@example.com",
            "password": "intruder-pass-1",
            "display_name": "Intruder",
        },
    )
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


class TestTackle:
    async def test_crud_round_trip(self, client, auth_headers):
        created = await client.post(
            "/api/v1/tackle",
            headers=auth_headers,
            json={
                "category": "rod",
                "name": "Light spin 3-15 g",
                "weight_min_g": 3,
                "weight_max_g": 15,
                "fishing_styles": ["light_spin"],
                "is_favourite": True,
            },
        )
        assert created.status_code == 201
        item_id = created.json()["id"]

        listed = await client.get("/api/v1/tackle", headers=auth_headers)
        assert len(listed.json()) == 1

        patched = await client.patch(
            f"/api/v1/tackle/{item_id}",
            headers=auth_headers,
            json={"name": "Light spin 3-15 g (yeni)", "weight_max_g": 18},
        )
        assert patched.json()["name"] == "Light spin 3-15 g (yeni)"
        assert patched.json()["weight_max_g"] == 18

        assert (
            await client.delete(f"/api/v1/tackle/{item_id}", headers=auth_headers)
        ).status_code == 204
        assert (
            await client.get(f"/api/v1/tackle/{item_id}", headers=auth_headers)
        ).status_code == 404

    async def test_editing_clears_the_ai_flag(self, client, auth_headers):
        """A user edit is an implicit confirmation of an AI-identified item."""
        created = await client.post(
            "/api/v1/tackle",
            headers=auth_headers,
            json={"category": "jighead", "name": "Jighead 7 g", "ai_identified": True},
        )
        assert created.json()["ai_identified"] is True
        patched = await client.patch(
            f"/api/v1/tackle/{created.json()['id']}",
            headers=auth_headers,
            json={"weight_min_g": 7, "weight_max_g": 7},
        )
        assert patched.json()["ai_identified"] is False

    async def test_invalid_category_rejected(self, client, auth_headers):
        response = await client.post(
            "/api/v1/tackle",
            headers=auth_headers,
            json={"category": "harpoon", "name": "Nope"},
        )
        assert response.status_code == 422

    async def test_nothing_is_ever_bulk_added(self, client, auth_headers):
        """There is no starter set, and no endpoint that fills a bag at once.

        There used to be: onboarding offered template kits — LRF, light spin,
        surf — and a tap added a dozen named items, minnow included. The
        angler who tapped one then had an inventory describing gear he had
        never owned, and every recommendation read from it. He was told to
        fish a minnow on a shore where he had none. A plausible inventory is
        worse than an empty one, because the empty one makes the assistant
        ask.
        """
        assert (
            await client.get("/api/v1/tackle/templates", headers=auth_headers)
        ).status_code == 404
        assert (
            await client.post("/api/v1/tackle/templates?keys=lrf", headers=auth_headers)
        ).status_code in (404, 405)
        assert (await client.get("/api/v1/tackle", headers=auth_headers)).json() == []

    async def test_cannot_read_another_users_tackle(self, client, auth_headers, other_headers):
        created = await client.post(
            "/api/v1/tackle",
            headers=auth_headers,
            json={"category": "rod", "name": "Private rod"},
        )
        response = await client.get(f"/api/v1/tackle/{created.json()['id']}", headers=other_headers)
        assert response.status_code == 404


class TestSessions:
    async def test_full_lifecycle(self, client, auth_headers):
        started = await client.post(
            "/api/v1/sessions",
            headers=auth_headers,
            json={
                "target_species": ["levrek"],
                "location_label": "Bostanlı",
                "latitude": 38.4612345,
                "longitude": 27.0987654,
                "fishing_style": "light_spin",
            },
        )
        assert started.status_code == 201
        session_id = started.json()["id"]
        assert started.json()["status"] == "active"
        # Default consent is "approximate": coordinates must be coarsened.
        assert len(started.json()["weather_snapshots"]) == 1

        active = await client.get("/api/v1/sessions/active", headers=auth_headers)
        assert active.json()["id"] == session_id

        observed = await client.post(
            f"/api/v1/sessions/{session_id}/observations",
            headers=auth_headers,
            json={"kind": "bite", "text": "Bir vuruş aldım."},
        )
        assert observed.status_code == 201

        catch = await client.post(
            "/api/v1/catches",
            headers=auth_headers,
            json={"species": "levrek", "length_cm": 44, "lure_label": "Minnow 11 g"},
        )
        assert catch.status_code == 201
        assert catch.json()["session_id"] == session_id

        finished = await client.post(
            f"/api/v1/sessions/{session_id}/finish",
            headers=auth_headers,
            json={"notes": "Güzel bir akşamdı."},
        )
        assert finished.status_code == 200
        body = finished.json()
        assert body["status"] == "completed"
        assert body["catch_count"] == 1
        assert "OTURUM ÖZETİ" in body["summary"]
        assert "Minnow 11 g" in body["summary"]

    async def test_only_one_active_session(self, client, auth_headers):
        await client.post("/api/v1/sessions", headers=auth_headers, json={})
        second = await client.post("/api/v1/sessions", headers=auth_headers, json={})
        assert second.status_code == 409

    async def test_coordinates_coarsened_at_approximate_consent(self, client, auth_headers):
        """Consent is honoured at write time, not just at display time."""
        await client.patch(
            "/api/v1/me/preferences",
            headers=auth_headers,
            json={"location_precision": "approximate"},
        )
        await client.post(
            "/api/v1/sessions",
            headers=auth_headers,
            json={"latitude": 38.4612345, "longitude": 27.0987654, "location_label": "Spot"},
        )
        spots = (await client.get("/api/v1/spots", headers=auth_headers)).json()
        assert spots[0]["latitude"] == 38.46
        assert spots[0]["longitude"] == 27.1

    async def test_no_location_consent_stores_nothing(self, client, auth_headers):
        await client.patch(
            "/api/v1/me/preferences",
            headers=auth_headers,
            json={"location_precision": "none"},
        )
        await client.post(
            "/api/v1/sessions",
            headers=auth_headers,
            json={"latitude": 38.46, "longitude": 27.09, "location_label": "Secret spot"},
        )
        spots = (await client.get("/api/v1/spots", headers=auth_headers)).json()
        assert spots[0]["latitude"] is None

    async def test_active_returns_404_when_none(self, client, auth_headers):
        response = await client.get("/api/v1/sessions/active", headers=auth_headers)
        assert response.status_code == 404
        assert response.json()["error"]["code"] == "not_found"

    async def test_cannot_finish_another_users_session(self, client, auth_headers, other_headers):
        started = await client.post("/api/v1/sessions", headers=auth_headers, json={})
        response = await client.post(
            f"/api/v1/sessions/{started.json()['id']}/finish",
            headers=other_headers,
            json={},
        )
        assert response.status_code == 404

    async def test_cannot_finish_twice(self, client, auth_headers):
        started = await client.post("/api/v1/sessions", headers=auth_headers, json={})
        sid = started.json()["id"]
        await client.post(f"/api/v1/sessions/{sid}/finish", headers=auth_headers, json={})
        again = await client.post(f"/api/v1/sessions/{sid}/finish", headers=auth_headers, json={})
        assert again.status_code == 409


class TestCatches:
    async def test_ai_identification_is_editable(self, client, auth_headers):
        """AI-generated information must never be immutable."""
        created = await client.post(
            "/api/v1/catches",
            headers=auth_headers,
            json={
                "species": "çipura",
                "length_cm": 30,
                "ai_confidence": 0.55,
                "ai_reasoning": "Gövde profili.",
            },
        )
        assert created.status_code == 201
        body = created.json()
        assert body["species_key"] == "sparus_aurata"
        assert body["species_confirmed_by_user"] is False

        corrected = await client.patch(
            f"/api/v1/catches/{body['id']}",
            headers=auth_headers,
            json={"species": "levrek"},
        )
        assert corrected.json()["species_key"] == "dicentrarchus_labrax"
        assert corrected.json()["species_confirmed_by_user"] is True
        # The old confidence no longer describes this row.
        assert corrected.json()["ai_confidence"] is None

    async def test_confirm_species_endpoint(self, client, auth_headers):
        created = await client.post(
            "/api/v1/catches",
            headers=auth_headers,
            json={"species": "levrek", "ai_confidence": 0.8},
        )
        confirmed = await client.post(
            f"/api/v1/catches/{created.json()['id']}/confirm-species", headers=auth_headers
        )
        assert confirmed.json()["species_confirmed_by_user"] is True

    async def test_species_filter_and_catalogue(self, client, auth_headers):
        await client.post("/api/v1/catches", headers=auth_headers, json={"species": "levrek"})
        await client.post("/api/v1/catches", headers=auth_headers, json={"species": "çipura"})
        filtered = await client.get("/api/v1/catches?species=seabass", headers=auth_headers)
        assert len(filtered.json()) == 1

        catalogue = await client.get("/api/v1/catches/species", headers=auth_headers)
        assert any(s["key"] == "sparus_aurata" for s in catalogue.json()["species"])

    async def test_absurd_measurements_rejected(self, client, auth_headers):
        response = await client.post(
            "/api/v1/catches",
            headers=auth_headers,
            json={"species": "levrek", "length_cm": 900},
        )
        assert response.status_code == 422

    async def test_deleting_catch_updates_session_count(self, client, auth_headers):
        session = await client.post("/api/v1/sessions", headers=auth_headers, json={})
        sid = session.json()["id"]
        catch = await client.post(
            "/api/v1/catches", headers=auth_headers, json={"species": "levrek"}
        )
        assert (await client.get(f"/api/v1/sessions/{sid}", headers=auth_headers)).json()[
            "catch_count"
        ] == 1
        await client.delete(f"/api/v1/catches/{catch.json()['id']}", headers=auth_headers)
        assert (await client.get(f"/api/v1/sessions/{sid}", headers=auth_headers)).json()[
            "catch_count"
        ] == 0

    async def test_cannot_read_another_users_catch(self, client, auth_headers, other_headers):
        created = await client.post(
            "/api/v1/catches", headers=auth_headers, json={"species": "levrek"}
        )
        response = await client.get(
            f"/api/v1/catches/{created.json()['id']}", headers=other_headers
        )
        assert response.status_code == 404


class TestHomeAndConditions:
    async def test_home_works_without_location(self, client, auth_headers):
        response = await client.get("/api/v1/home", headers=auth_headers)
        assert response.status_code == 200
        body = response.json()
        assert body["display_name"] == "Serkan"
        assert body["conditions"] is None
        assert body["tackle_count"] == 0

    async def test_home_includes_conditions_when_located(self, client, auth_headers):
        response = await client.get(
            "/api/v1/home?latitude=38.46&longitude=27.09", headers=auth_headers
        )
        assert response.json()["conditions"]["summary"]

    async def test_home_surfaces_active_session(self, client, auth_headers):
        await client.post(
            "/api/v1/sessions",
            headers=auth_headers,
            json={"location_label": "Bostanlı"},
        )
        body = (await client.get("/api/v1/home", headers=auth_headers)).json()
        assert body["active_session"]["location_label"] == "Bostanlı"

    async def test_conditions_validates_coordinates(self, client, auth_headers):
        response = await client.get(
            "/api/v1/conditions?latitude=999&longitude=0", headers=auth_headers
        )
        assert response.status_code == 422

    async def test_recommendation_endpoint(self, client, auth_headers):
        # Spelled out item by item, because that is the only way an inventory
        # is ever filled now.
        for item in (
            {"category": "rod", "name": "Light spin 3-15 g", "weight_min_g": 3, "weight_max_g": 15},
            {"category": "reel", "name": "3000 reel"},
            {"category": "minnow", "name": "Minnow 7 cm", "weight_min_g": 8, "weight_max_g": 8},
            {"category": "line", "name": "PE 0.8", "line_rating": "PE 0.8"},
        ):
            created = await client.post("/api/v1/tackle", headers=auth_headers, json=item)
            assert created.status_code == 201
        response = await client.post(
            "/api/v1/recommendation",
            headers=auth_headers,
            json={"target_species": "levrek", "latitude": 38.46, "longitude": 27.09},
        )
        assert response.status_code == 200
        body = response.json()
        assert body["technique"]
        assert body["reasons"]
        assert {r["kind"] for r in body["reasons"]} <= {
            "observation",
            "inference",
            "history",
        }


class TestErrorContract:
    async def test_404_shape(self, client, auth_headers):
        response = await client.get("/api/v1/catches/does-not-exist", headers=auth_headers)
        assert response.status_code == 404
        assert set(response.json()["error"]) >= {"code", "message"}

    async def test_validation_error_shape(self, client, auth_headers):
        response = await client.post(
            "/api/v1/tackle", headers=auth_headers, json={"category": "rod"}
        )
        assert response.status_code == 422
        error = response.json()["error"]
        assert error["code"] == "validation_error"
        assert error["details"]["problems"]

    async def test_no_stack_traces_leak(self, client, auth_headers):
        response = await client.get("/api/v1/catches/nope", headers=auth_headers)
        assert "Traceback" not in response.text
        assert "sqlalchemy" not in response.text.lower()

    async def test_request_id_is_echoed(self, client):
        response = await client.get("/api/v1/health")
        assert response.headers["x-request-id"]

    async def test_supplied_request_id_is_preserved(self, client):
        response = await client.get("/api/v1/health", headers={"x-request-id": "trace-me-123"})
        assert response.headers["x-request-id"] == "trace-me-123"


class TestHealth:
    async def test_health_needs_no_auth(self, client):
        response = await client.get("/api/v1/health")
        assert response.status_code == 200
        assert response.json()["status"] == "ok"

    async def test_readiness_checks_database(self, client):
        assert (await client.get("/api/v1/health/ready")).json()["status"] == "ready"

    async def test_metrics_available_outside_production(self, client):
        response = await client.get("/api/v1/health/metrics")
        assert response.status_code == 200
        assert "counters" in response.json()
