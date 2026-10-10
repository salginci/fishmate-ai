"""Tool execution endpoint — the server half of Gemini function calling.

The device holds the Live WebSocket, so `toolCall` messages arrive there. The
device does not have the database, the weather provider or the user's history,
and must not be trusted to decide what a tool returns. So it forwards the call
here, authenticated as the user, and we execute it.

Batching matters: Gemini can emit several functionCalls in a single toolCall
message and expects one `toolResponse` containing all the functionResponses.
"""

from __future__ import annotations

from fastapi import APIRouter

from app.core.observability import get_logger
from app.deps import CurrentUser, DbSession, user_language
from app.schemas.api import (
    ToolCallBatchRequest,
    ToolCallBatchResponse,
    ToolCallRequest,
    ToolCallResult,
)
from app.tools.registry import ToolContext, get_tool_registry

log = get_logger("tools_api")
router = APIRouter(tags=["tools"])


def _context(user, db, call: ToolCallRequest) -> ToolContext:
    return ToolContext(
        db=db,
        user=user,
        language=user_language(user, call.language),
        live_session_ref=call.live_session_ref,
        latitude=call.latitude,
        longitude=call.longitude,
    )


@router.post("/tools/execute", response_model=ToolCallResult)
async def execute_tool(body: ToolCallRequest, user: CurrentUser, db: DbSession) -> ToolCallResult:
    registry = get_tool_registry()
    result = await registry.execute(body.name, body.args, _context(user, db, body))
    # Commit here rather than per-tool: one toolCall is one unit of work, and a
    # mutation plus its audit row should land together.
    await db.commit()
    return ToolCallResult(
        name=body.name,
        call_id=body.call_id,
        ok=result.ok,
        response=result.to_function_response(),
        error_code=result.error_code,
    )


@router.post("/tools/execute-batch", response_model=ToolCallBatchResponse)
async def execute_tools(
    body: ToolCallBatchRequest, user: CurrentUser, db: DbSession
) -> ToolCallBatchResponse:
    registry = get_tool_registry()
    results: list[ToolCallResult] = []
    for call in body.calls:
        # Sequential on purpose: several calls in one turn can touch the same
        # session row (e.g. log_catch then add_session_observation), and the
        # AsyncSession is not safe for concurrent use anyway.
        result = await registry.execute(call.name, call.args, _context(user, db, call))
        results.append(
            ToolCallResult(
                name=call.name,
                call_id=call.call_id,
                ok=result.ok,
                response=result.to_function_response(),
                error_code=result.error_code,
            )
        )
    await db.commit()
    log.info(
        "tools.batch_executed",
        count=len(results),
        failed=sum(1 for r in results if not r.ok),
    )
    return ToolCallBatchResponse(results=results)


@router.get("/tools")
async def list_tools(user: CurrentUser) -> dict:
    """Introspection for debugging and the Settings -> About screen."""
    registry = get_tool_registry()
    return {
        "tools": [
            {
                "name": name,
                "mutates": registry.get(name).mutates,  # type: ignore[union-attr]
                "description": registry.get(name).description,  # type: ignore[union-attr]
            }
            for name in registry.names()
        ]
    }
