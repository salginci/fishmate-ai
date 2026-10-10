"""FastAPI application factory, middleware and error handling."""

from __future__ import annotations

import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api.v1 import auth, catches, conditions, demo, live, sessions, tackle, tools
from app.config import get_settings
from app.core.errors import FishMateError
from app.core.observability import (
    configure_logging,
    get_logger,
    metrics,
    new_request_id,
    request_id_ctx,
    user_id_ctx,
)
from app.db.session import dispose_engine

log = get_logger("app")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    configure_logging(settings.log_level, settings.log_json)

    problems = settings.startup_problems()
    for problem in problems:
        log.warning("startup.config_problem", problem=problem)
    if problems and settings.is_production:
        # Fail fast rather than serve a production deployment with a missing
        # AUTH_SECRET or a wildcard CORS policy.
        raise RuntimeError(
            "Refusing to start in production with configuration problems: " + "; ".join(problems)
        )

    log.info(
        "startup",
        environment=settings.environment,
        live_model=settings.gemini_live_model,
        live_api_configured=settings.live_api_configured,
        weather_provider=settings.weather_provider,
        demo_mode=settings.demo_mode_enabled,
    )
    try:
        yield
    finally:
        await dispose_engine()
        log.info("shutdown")


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging(settings.log_level, settings.log_json)

    app = FastAPI(
        title="FishMate AI",
        description=(
            "Backend for the FishMate AI fishing companion: authentication, "
            "constrained Gemini Live token minting, the fishing tool layer and "
            "fishing memory."
        ),
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None if settings.is_production else "/docs",
        redoc_url=None,
        openapi_url=None if settings.is_production else "/openapi.json",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # ---- request context + access log ------------------------------------
    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("x-request-id") or new_request_id()
        request_id_ctx.set(request_id)
        user_id_ctx.set("-")
        started = time.perf_counter()
        route = request.url.path
        try:
            response = await call_next(request)
        except Exception:
            duration = (time.perf_counter() - started) * 1000
            metrics.incr("http.requests", method=request.method, status="500")
            log.exception(
                "http.unhandled",
                method=request.method,
                path=route,
                duration_ms=round(duration, 1),
            )
            raise
        duration = (time.perf_counter() - started) * 1000
        response.headers["x-request-id"] = request_id
        metrics.incr("http.requests", method=request.method, status=str(response.status_code))
        metrics.observe("http.latency", duration / 1000, method=request.method)
        # Health checks would otherwise dominate the log.
        if not route.startswith(f"{settings.api_prefix}/health"):
            log.info(
                "http.request",
                method=request.method,
                path=route,
                status=response.status_code,
                duration_ms=round(duration, 1),
            )
        return response

    # ---- error handling ---------------------------------------------------
    @app.exception_handler(FishMateError)
    async def fishmate_error_handler(_request: Request, exc: FishMateError):
        return JSONResponse(status_code=exc.status_code, content={"error": exc.to_payload()})

    @app.exception_handler(RequestValidationError)
    async def validation_handler(_request: Request, exc: RequestValidationError):
        problems = [
            {
                "field": ".".join(str(p) for p in e["loc"][1:]) or "(body)",
                "problem": e["msg"],
            }
            for e in exc.errors()[:8]
        ]
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "validation_error",
                    "message": "Some of the supplied values were not valid.",
                    "details": {"problems": problems},
                }
            },
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(_request: Request, exc: StarletteHTTPException):
        codes = {401: "unauthorized", 403: "forbidden", 404: "not_found", 405: "method_not_allowed"}
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "error": {
                    "code": codes.get(exc.status_code, "http_error"),
                    "message": str(exc.detail),
                }
            },
        )

    @app.exception_handler(Exception)
    async def unhandled_handler(_request: Request, exc: Exception):
        """Last resort: never leak a stack trace or provider message."""
        log.exception("app.unhandled_exception", error_type=type(exc).__name__)
        return JSONResponse(
            status_code=500,
            content={
                "error": {
                    "code": "internal_error",
                    "message": "Something went wrong on our side. Please try again.",
                }
            },
        )

    # ---- routes -----------------------------------------------------------
    prefix = settings.api_prefix
    app.include_router(conditions.health_router, prefix=prefix)
    app.include_router(auth.router, prefix=prefix)
    app.include_router(live.router, prefix=prefix)
    app.include_router(tools.router, prefix=prefix)
    app.include_router(tackle.router, prefix=prefix)
    app.include_router(sessions.router, prefix=prefix)
    app.include_router(catches.router, prefix=prefix)
    app.include_router(conditions.router, prefix=prefix)
    if settings.demo_mode_enabled and not settings.is_production:
        app.include_router(demo.router, prefix=prefix)

    @app.get("/", include_in_schema=False)
    async def root() -> dict:
        return {
            "name": "FishMate AI",
            "version": "0.1.0",
            "docs": None if settings.is_production else "/docs",
            "health": f"{prefix}/health",
        }

    return app


app = create_app()
