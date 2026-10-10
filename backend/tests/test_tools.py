"""Tool layer: validation, execution, audit and the no-fabrication contract."""

from __future__ import annotations

from sqlalchemy import select

from app.db.models import LocationPrecision, ToolExecution
from app.repositories import users as user_repo
from app.tools.declarations import DEVICE_TOOLS, FUNCTION_DECLARATIONS, TOOL_NAMES
from app.tools.registry import ToolContext, get_tool_registry


class TestDeclarations:
    def test_every_declaration_is_well_formed(self):
        for declaration in FUNCTION_DECLARATIONS:
            assert declaration["name"]
            assert len(declaration["description"]) > 40, declaration["name"]
            params = declaration.get("parameters", {})
            assert params.get("type") == "OBJECT", declaration["name"]
            for field, schema in (params.get("properties") or {}).items():
                assert schema.get("type") in {
                    "STRING",
                    "NUMBER",
                    "INTEGER",
                    "BOOLEAN",
                    "ARRAY",
                    "OBJECT",
                }, f"{declaration['name']}.{field}"
                assert schema.get("description") or schema.get("enum"), (
                    f"{declaration['name']}.{field} needs a description"
                )

    def test_required_fields_exist_in_properties(self):
        for declaration in FUNCTION_DECLARATIONS:
            params = declaration.get("parameters", {})
            properties = set((params.get("properties") or {}).keys())
            for required in params.get("required", []):
                assert required in properties, declaration["name"]

    def test_names_unique(self):
        assert len(TOOL_NAMES) == len(set(TOOL_NAMES))

    def test_every_declared_tool_has_a_handler(self):
        """Every tool but the device ones, which the client executes itself."""
        assert set(get_tool_registry().names()) == set(TOOL_NAMES) - DEVICE_TOOLS

    def test_device_tools_are_declared_but_not_server_executable(self):
        # Declared, because the declaration is what the model sees and it has
        # to be minted with the session token. Not executable here, because the
        # server cannot reach a phone's camera — if it could answer the call,
        # the model would be told it can see while nothing turned on.
        assert DEVICE_TOOLS
        assert set(TOOL_NAMES) >= DEVICE_TOOLS
        assert not DEVICE_TOOLS & set(get_tool_registry().names())


class TestRegistryValidation:
    async def test_unknown_tool_is_reported_not_raised(self, db, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "drop_database", {}, ToolContext(db=s, user=user)
            )
        assert not result.ok
        assert result.error_code == "unknown_tool"
        assert "available_tools" in result.payload

    async def test_device_tool_posted_here_says_so_plainly(self, db, registered, session_factory):
        """A client that forgot to intercept it must not be sent to the wrong file."""
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "set_camera", {"enabled": True}, ToolContext(db=s, user=user)
            )
        assert not result.ok
        assert result.error_code == "device_tool"
        assert "device" in result.payload["message"]
        # And the model is told not to describe anything it cannot see.
        assert "do not retry" in result.payload["instruction_to_model"].lower()

    async def test_invalid_arguments_return_structured_problems(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "get_recent_catches", {"limit": 99999}, ToolContext(db=s, user=user)
            )
        assert not result.ok
        assert result.error_code == "invalid_arguments"
        assert result.payload["problems"][0]["field"] == "limit"
        # The model is told how to recover rather than left to improvise.
        assert "instruction_to_model" in result.payload

    async def test_extra_argument_is_rejected(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "log_catch",
                {"species": "levrek", "sql": "DROP TABLE users"},
                ToolContext(db=s, user=user),
            )
        assert not result.ok
        assert result.error_code == "invalid_arguments"

    async def test_every_failure_is_audited(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await get_tool_registry().execute(
                "get_recent_catches", {"limit": -5}, ToolContext(db=s, user=user)
            )
            await s.commit()
        async with session_factory() as s:
            rows = list((await s.execute(select(ToolExecution))).scalars())
        assert len(rows) == 1
        assert rows[0].succeeded is False
        assert rows[0].error_code == "invalid_arguments"

    async def test_success_is_audited_with_coarse_coordinates(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "get_current_conditions",
                {"latitude": 38.456789, "longitude": 27.098765},
                ToolContext(db=s, user=user),
            )
            await s.commit()
        assert result.ok
        async with session_factory() as s:
            row = (await s.execute(select(ToolExecution))).scalars().first()
        # Precise fishing spots must not land in the audit trail.
        assert row.arguments["latitude"] == 38.46
        assert row.arguments["longitude"] == 27.1


class TestToolBehaviour:
    async def test_conditions_returns_measurements(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "get_current_conditions",
                {"latitude": 38.46, "longitude": 27.09},
                ToolContext(db=s, user=user),
            )
        assert result.ok
        assert "summary" in result.payload
        assert result.payload["wind_speed_kmh"] is not None

    async def test_conditions_without_location_refuses_to_guess(self, registered, session_factory):
        """The model must never narrate invented weather."""
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await user_repo.update_preferences(
                s, user, location_precision=LocationPrecision.NONE.value
            )
            result = await get_tool_registry().execute(
                "get_current_conditions", {}, ToolContext(db=s, user=user)
            )
        assert result.ok  # a clean answer, not a crash
        assert result.payload["error"] == "no_location"
        assert "Do NOT guess" in result.payload["instruction_to_model"]

    async def test_empty_tackle_tells_model_not_to_invent_gear(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "get_tackle_inventory", {}, ToolContext(db=s, user=user)
            )
        assert result.ok
        assert result.payload["items"] == []
        assert "Do NOT recommend specific gear" in result.payload["instruction_to_model"]

    async def test_no_history_tells_model_not_to_invent_catches(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "get_recent_catches", {"species": "levrek"}, ToolContext(db=s, user=user)
            )
        assert result.ok
        assert result.payload["total_matching_catches"] == 0
        assert "Do not invent" in result.payload["instruction_to_model"]

    async def test_regulations_are_verified_only_with_a_citation(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "get_fishing_regulations",
                {"species": "levrek", "region": "TR"},
                ToolContext(db=s, user=user),
            )
        assert result.ok
        # Levrek is in the circular, so this is the verified branch -- and a
        # verified answer must name where the number came from and when it
        # took effect. "Never verified" was only ever true because the data
        # file shipped empty.
        assert result.payload["verified"] is True
        assert result.payload["minimum_length_cm"] == 25
        assert result.payload["sources"][0]["url"]
        assert result.payload["sources"][0]["effective_from"]
        assert "cited official source" in result.payload["instruction_to_model"]

    async def test_finish_without_active_session_is_graceful(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "finish_fishing_session", {}, ToolContext(db=s, user=user)
            )
        assert result.ok
        assert result.payload["no_active_session"] is True

    async def test_session_lifecycle_through_tools(self, registered, session_factory):
        """start -> log -> observe -> finish, the hands-free happy path."""
        registry = get_tool_registry()
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            ctx = ToolContext(db=s, user=user, latitude=38.46, longitude=27.09)

            started = await registry.execute(
                "start_fishing_session",
                {"target_species": ["levrek"], "location_label": "Bostanlı"},
                ctx,
            )
            assert started.ok, started.payload
            assert started.payload["session_id"]
            assert started.payload["conditions_at_start"]

            # Starting twice must be refused, not duplicated.
            again = await registry.execute("start_fishing_session", {}, ctx)
            assert again.payload["already_active"] is True

            logged = await registry.execute(
                "log_catch",
                {
                    "species": "levrek",
                    "length_cm": 42,
                    "lure_label": "Minnow 11 cm / 11 g",
                    "ai_confidence": 0.72,
                    "ai_reasoning": "Çizgili gövde ve sivri ağız yapısı.",
                },
                ctx,
            )
            assert logged.ok
            assert logged.payload["identification"] == "ai"
            # An AI guess must be presented as a likelihood.
            assert "likelihood" in logged.payload["instruction_to_model"]

            observed = await registry.execute(
                "add_session_observation",
                {"kind": "bite", "text": "Bir vuruş daha aldım."},
                ctx,
            )
            assert observed.ok

            finished = await registry.execute("finish_fishing_session", {}, ctx)
            assert finished.ok
            assert finished.payload["catches"] == 1
            # English is the product default; Turkish is covered in test_memory.py.
            assert "SESSION SUMMARY" in finished.payload["report"]
            await s.commit()

    async def test_log_catch_requires_species(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "log_catch", {"length_cm": 30}, ToolContext(db=s, user=user)
            )
        assert not result.ok
        assert result.error_code == "invalid_arguments"

    async def test_user_stated_species_is_marked_confirmed(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "log_catch",
                {"species": "çipura", "length_cm": 28},
                ToolContext(db=s, user=user),
            )
            await s.commit()
        assert result.payload["identification"] == "user_stated"


class TestToolApi:
    async def test_execute_endpoint_requires_auth(self, client):
        response = await client.post(
            "/api/v1/tools/execute", json={"name": "get_current_conditions", "args": {}}
        )
        assert response.status_code == 401

    async def test_execute_endpoint_round_trip(self, client, auth_headers):
        response = await client.post(
            "/api/v1/tools/execute",
            headers=auth_headers,
            json={
                "name": "get_current_conditions",
                "call_id": "call-1",
                "args": {"latitude": 38.46, "longitude": 27.09},
            },
        )
        assert response.status_code == 200
        body = response.json()
        assert body["ok"] is True
        assert body["call_id"] == "call-1"  # echoed for matching
        assert "summary" in body["response"]

    async def test_batch_preserves_order_and_ids(self, client, auth_headers):
        response = await client.post(
            "/api/v1/tools/execute-batch",
            headers=auth_headers,
            json={
                "calls": [
                    {"name": "get_tackle_inventory", "call_id": "a", "args": {}},
                    {"name": "get_recent_catches", "call_id": "b", "args": {}},
                    {"name": "nope", "call_id": "c", "args": {}},
                ]
            },
        )
        assert response.status_code == 200
        results = response.json()["results"]
        assert [r["call_id"] for r in results] == ["a", "b", "c"]
        assert results[2]["ok"] is False

    async def test_batch_rejects_oversized_request(self, client, auth_headers):
        response = await client.post(
            "/api/v1/tools/execute-batch",
            headers=auth_headers,
            json={"calls": [{"name": "get_tackle_inventory", "args": {}}] * 9},
        )
        assert response.status_code == 422

    async def test_tool_list_endpoint(self, client, auth_headers):
        response = await client.get("/api/v1/tools", headers=auth_headers)
        assert response.status_code == 200
        tools = response.json()["tools"]
        assert len(tools) == len(set(TOOL_NAMES) - DEVICE_TOOLS)
        assert any(t["mutates"] for t in tools)

    async def test_user_cannot_read_another_users_data(self, client, auth_headers):
        """Tools are scoped to the authenticated user, with no user_id argument."""
        other = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "other@example.com",
                "password": "other-password-1",
                "display_name": "Other",
            },
        )
        other_headers = {"Authorization": f"Bearer {other.json()['access_token']}"}
        await client.post(
            "/api/v1/tools/execute",
            headers=auth_headers,
            json={"name": "log_catch", "args": {"species": "levrek", "length_cm": 50}},
        )
        response = await client.post(
            "/api/v1/tools/execute",
            headers=other_headers,
            json={"name": "get_recent_catches", "args": {}},
        )
        assert response.json()["response"]["total_matching_catches"] == 0
