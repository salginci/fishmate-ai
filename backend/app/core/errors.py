"""Domain errors and the user-facing message contract.

Every error surfaced to a client carries a stable machine `code` and a short
human `message`. Stack traces and provider internals never cross the boundary
(docs/SECURITY.md). The Android client maps `code` -> localized string, falling
back to `message`.
"""

from __future__ import annotations

from typing import Any


class FishMateError(Exception):
    """Base class for errors that are safe to show a user."""

    status_code = 500
    code = "internal_error"
    message = "Something went wrong on our side. Please try again."

    def __init__(self, message: str | None = None, **details: Any) -> None:
        self.message = message or self.message
        self.details = details
        super().__init__(self.message)

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"code": self.code, "message": self.message}
        if self.details:
            payload["details"] = self.details
        return payload


class NotFoundError(FishMateError):
    status_code = 404
    code = "not_found"
    message = "That record could not be found."


class ValidationError(FishMateError):
    status_code = 422
    code = "validation_error"
    message = "Some of the supplied values were not valid."


class AuthError(FishMateError):
    status_code = 401
    code = "unauthorized"
    message = "Please sign in again."


class ForbiddenError(FishMateError):
    status_code = 403
    code = "forbidden"
    message = "You do not have access to that."


class ConflictError(FishMateError):
    status_code = 409
    code = "conflict"
    message = "That action conflicts with the current state."


class LiveTokenError(FishMateError):
    status_code = 503
    code = "live_token_unavailable"
    message = "I can't reach Gemini right now. Check your connection and try again."


class UpstreamUnavailableError(FishMateError):
    status_code = 503
    code = "upstream_unavailable"
    message = "I couldn't retrieve that information right now."


class ToolExecutionError(FishMateError):
    status_code = 400
    code = "tool_execution_failed"
    message = "I couldn't complete that action."


class RateLimitedError(FishMateError):
    status_code = 429
    code = "rate_limited"
    message = "That was a bit fast — give me a moment and try again."
