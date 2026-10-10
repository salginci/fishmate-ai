"""Authentication, preferences and account deletion."""

from __future__ import annotations

import pytest

from app.core.errors import AuthError
from app.core.security import (
    create_access_token,
    decode_access_token,
    hash_password,
    verify_password,
)


class TestPasswordHashing:
    def test_round_trip(self):
        hashed = hash_password("shore-fishing-123")
        assert hashed != "shore-fishing-123"
        assert verify_password("shore-fishing-123", hashed)
        assert not verify_password("wrong", hashed)

    def test_rejects_overlong_password(self):
        # bcrypt silently truncates past 72 bytes; we refuse instead so a user
        # cannot be authenticated by a prefix of their password.
        with pytest.raises(AuthError):
            hash_password("x" * 73)

    def test_verify_handles_garbage_hash(self):
        assert not verify_password("anything", "not-a-bcrypt-hash")


class TestJwt:
    def test_round_trip(self):
        token, expires_in = create_access_token("user-123")
        assert expires_in > 0
        assert decode_access_token(token)["sub"] == "user-123"

    def test_rejects_tampered_token(self):
        token, _ = create_access_token("user-123")
        with pytest.raises(AuthError):
            decode_access_token(token[:-4] + "aaaa")

    def test_rejects_garbage(self):
        with pytest.raises(AuthError):
            decode_access_token("not.a.jwt")


class TestAuthApi:
    async def test_register_returns_token_and_user(self, client):
        response = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "New@Example.com",
                "password": "password-1234",
                "display_name": "Ayşe",
                "language": "tr",
            },
        )
        assert response.status_code == 201
        body = response.json()
        assert body["token_type"] == "bearer"
        assert body["user"]["email"] == "new@example.com"  # normalised
        assert body["user"]["preferences"]["language"] == "tr"
        assert "hashed_password" not in str(body)

    async def test_register_does_not_seed_tackle(self, client, auth_headers):
        """Recommending gear the user does not own would be worse than nothing."""
        response = await client.get("/api/v1/tackle", headers=auth_headers)
        assert response.status_code == 200
        assert response.json() == []

    async def test_duplicate_email_conflicts(self, client, registered):
        response = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "angler@example.com",
                "password": "another-password",
                "display_name": "Someone",
            },
        )
        assert response.status_code == 409
        assert response.json()["error"]["code"] == "conflict"

    async def test_login_succeeds(self, client, registered):
        response = await client.post(
            "/api/v1/auth/login",
            json={"email": "angler@example.com", "password": "shore-fishing-123"},
        )
        assert response.status_code == 200
        assert response.json()["access_token"]

    @pytest.mark.parametrize(
        ("email", "password"),
        [
            ("angler@example.com", "wrong-password"),
            ("nobody@example.com", "shore-fishing-123"),
        ],
    )
    async def test_login_failures_are_indistinguishable(self, client, registered, email, password):
        """Must not reveal whether an account exists."""
        response = await client.post(
            "/api/v1/auth/login", json={"email": email, "password": password}
        )
        assert response.status_code == 401
        assert response.json()["error"]["message"] == "Email or password is incorrect."

    async def test_short_password_rejected(self, client):
        response = await client.post(
            "/api/v1/auth/register",
            json={"email": "a@b.com", "password": "short", "display_name": "X"},
        )
        assert response.status_code == 422

    async def test_me_requires_auth(self, client):
        assert (await client.get("/api/v1/me")).status_code == 401

    async def test_me_rejects_malformed_header(self, client):
        response = await client.get("/api/v1/me", headers={"Authorization": "NotBearer abc"})
        assert response.status_code == 401

    async def test_me_returns_profile(self, client, auth_headers):
        response = await client.get("/api/v1/me", headers=auth_headers)
        assert response.status_code == 200
        assert response.json()["display_name"] == "Serkan"

    async def test_update_preferences(self, client, auth_headers):
        response = await client.patch(
            "/api/v1/me/preferences",
            headers=auth_headers,
            json={"location_precision": "none", "verbosity": "terse", "camera_fps": 0.25},
        )
        assert response.status_code == 200
        body = response.json()
        assert body["location_precision"] == "none"
        assert body["verbosity"] == "terse"
        assert body["camera_fps"] == 0.25

    async def test_invalid_preference_rejected(self, client, auth_headers):
        response = await client.patch(
            "/api/v1/me/preferences",
            headers=auth_headers,
            json={"location_precision": "satellite"},
        )
        assert response.status_code == 422

    async def test_account_deletion_removes_data(self, client, auth_headers):
        await client.post(
            "/api/v1/tackle",
            headers=auth_headers,
            json={"category": "rod", "name": "Test rod"},
        )
        assert (await client.delete("/api/v1/me", headers=auth_headers)).status_code == 204
        # The token still decodes, but the user is gone.
        assert (await client.get("/api/v1/me", headers=auth_headers)).status_code == 401
