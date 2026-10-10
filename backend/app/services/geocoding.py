"""Turning place names into coordinates, and coordinates into place names.

Two jobs, both in service of the same thing: the angler should be able to say
"how is it at Bostanlı?" and be answered, and a session should open knowing
where it is rather than asking.

**Why not Open-Meteo's geocoder**, when the weather already comes from there:
it was measured against the names this app's users actually say and it is
wrong for them. "Bostanlı" returned five inland villages (Mardin, Edirne,
Tunceli, Afyon, Adıyaman) and never the İzmir waterfront the angler means;
"Kilyos", one of Istanbul's best-known beaches, returned nothing at all. A
geocoder that confidently hands back a village 1000 km from the sea would make
the model report a sea state for a place with no sea, which is the exact class
of failure this codebase refuses to ship.

Nominatim finds those places, but **its own ranking is no better**: for
"Bostanlı" the İzmir neighbourhood came back last of twelve, behind villages
300-1400 km away, because global "importance" has nothing to do with which one
this angler means. So the ranking is ours: candidates are re-sorted by
distance from where the angler actually is, which put the right answer first
in every case tested. When that still leaves a genuine tie, the result is
*ambiguous* and the model asks — it never silently picks one.
"""

from __future__ import annotations

import asyncio
import math
import time
from dataclasses import dataclass
from typing import Any, Protocol

import httpx

from app.config import Settings, get_settings
from app.core.observability import Timer, get_logger, metrics, round_coords

log = get_logger("geocoding")

SEARCH_URL = "https://nominatim.openstreetmap.org/search"
REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"

#: How many candidates to pull before re-ranking.
#:
#: Twelve is not arbitrary: the İzmir "Bostanlı" that the test case needed
#: arrived tenth under Nominatim's own ordering, so a short list would have
#: dropped the only correct answer before our ranking ever saw it.
SEARCH_LIMIT = 12

#: A candidate this close to the angler is plausibly the one they mean.
NEAR_KM = 250.0

#: How much nearer the best candidate must be than the runner-up to be taken
#: without asking. Bostanlı: 5 km against 309 km, so 60x — far past this.
DECISIVE_RATIO = 2.5

#: How many options to offer when asking. More than a few is unusable aloud.
MAX_SUGGESTIONS = 4


@dataclass(frozen=True)
class Place:
    """One geocoding candidate."""

    name: str
    display_name: str
    latitude: float
    longitude: float
    kind: str | None = None
    region: str | None = None
    country: str | None = None
    country_code: str | None = None

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "name": self.name,
            "full_name": self.display_name,
            "latitude": round(self.latitude, 5),
            "longitude": round(self.longitude, 5),
        }
        for key, value in (
            ("kind", self.kind),
            ("region", self.region),
            ("country", self.country),
        ):
            if value:
                payload[key] = value
        return payload


def distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance. Haversine, not the law of cosines, because the
    latter loses precision for the short distances that decide a tie."""
    radius = 6371.0088
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * radius * math.asin(min(1.0, math.sqrt(a)))


@dataclass(frozen=True)
class Resolution:
    """Either one place, or several the user must choose between."""

    place: Place | None
    candidates: tuple[Place, ...]

    @property
    def ambiguous(self) -> bool:
        return self.place is None and bool(self.candidates)

    @property
    def empty(self) -> bool:
        return self.place is None and not self.candidates


def resolve(
    candidates: list[Place],
    reference: tuple[float, float] | None,
) -> Resolution:
    """Pick the place the angler means, or admit that it is unclear.

    Pure, and the only interesting logic in this module, so it is where the
    tests live (`TestResolve`).

    With a reference point, candidates are ordered by distance from it and the
    nearest wins only if it is both close and decisively closer than the next
    one. Without a reference point, anything but a single candidate is
    ambiguous: guessing between same-named places with nothing to go on is how
    an angler gets told the sea state of a landlocked village.
    """
    if not candidates:
        return Resolution(place=None, candidates=())

    if reference is None:
        if len(candidates) == 1:
            return Resolution(place=candidates[0], candidates=(candidates[0],))
        return Resolution(place=None, candidates=tuple(candidates[:MAX_SUGGESTIONS]))

    ref_lat, ref_lon = reference
    ranked = sorted(
        candidates,
        key=lambda p: distance_km(ref_lat, ref_lon, p.latitude, p.longitude),
    )
    nearest = ranked[0]
    nearest_km = distance_km(ref_lat, ref_lon, nearest.latitude, nearest.longitude)

    if len(ranked) == 1:
        return Resolution(place=nearest, candidates=(nearest,))

    runner_up_km = distance_km(ref_lat, ref_lon, ranked[1].latitude, ranked[1].longitude)
    decisive = nearest_km <= NEAR_KM and runner_up_km >= nearest_km * DECISIVE_RATIO
    if decisive:
        return Resolution(place=nearest, candidates=tuple(ranked[:MAX_SUGGESTIONS]))
    return Resolution(place=None, candidates=tuple(ranked[:MAX_SUGGESTIONS]))


class GeocodingProvider(Protocol):
    name: str

    async def search(self, name: str, country_code: str | None = None) -> list[Place]: ...

    async def reverse(self, latitude: float, longitude: float) -> Place | None: ...


class _TTLCache:
    def __init__(self, ttl_seconds: int) -> None:
        self._ttl = ttl_seconds
        self._entries: dict[str, tuple[float, Any]] = {}

    def get(self, key: str) -> Any | None:
        entry = self._entries.get(key)
        if entry is None:
            return None
        stored_at, value = entry
        if time.monotonic() - stored_at > self._ttl:
            del self._entries[key]
            return None
        return value

    def set(self, key: str, value: Any) -> None:
        self._entries[key] = (time.monotonic(), value)

    def clear(self) -> None:
        self._entries.clear()


class NominatimProvider:
    """OpenStreetMap's geocoder.

    Nominatim is free and asks for three things in return, all of which are
    honoured here: a real identifying User-Agent, at most one request a second,
    and no hammering for results you already have. The rate limit is enforced
    with a lock rather than trusted to luck, and a place name does not move, so
    the cache TTL is hours rather than minutes.
    """

    name = "nominatim"

    def __init__(self, settings: Settings | None = None) -> None:
        self._settings = settings or get_settings()
        self._cache = _TTLCache(self._settings.geocoding_cache_ttl_seconds)
        self._lock = asyncio.Lock()
        self._last_request_at = 0.0

    async def _get(self, url: str, params: dict[str, Any]) -> Any | None:
        headers = {"User-Agent": self._settings.geocoding_user_agent}
        async with self._lock:
            # Nominatim's usage policy is one request per second, absolute.
            wait = self._settings.geocoding_min_interval_seconds - (
                time.monotonic() - self._last_request_at
            )
            if wait > 0:
                await asyncio.sleep(wait)
            self._last_request_at = time.monotonic()
            try:
                async with httpx.AsyncClient() as client:
                    response = await client.get(
                        url,
                        params=params,
                        headers=headers,
                        timeout=self._settings.geocoding_timeout_seconds,
                    )
            except httpx.HTTPError as exc:
                # Never fatal: a missing place name costs a nicety, and a
                # failed lookup must not take the conversation down with it.
                log.warning("geocoding.transport_error", error=type(exc).__name__)
                metrics.incr("geocoding.transport_error")
                return None
        if response.status_code != 200:
            log.warning("geocoding.unexpected_status", status=response.status_code)
            metrics.incr("geocoding.unexpected_status", status=str(response.status_code))
            return None
        try:
            return response.json()
        except ValueError:
            log.warning("geocoding.bad_json")
            return None

    async def search(self, name: str, country_code: str | None = None) -> list[Place]:
        key = f"s:{name.strip().casefold()}:{country_code or ''}"
        cached = self._cache.get(key)
        if cached is not None:
            metrics.incr("geocoding.cache_hit", kind="search")
            return list(cached)

        params: dict[str, Any] = {
            "q": name,
            "format": "jsonv2",
            "limit": SEARCH_LIMIT,
            "addressdetails": 1,
        }
        if country_code:
            params["countrycodes"] = country_code.lower()

        metrics.incr("geocoding.fetch", kind="search")
        with Timer("geocoding.search_latency", provider=self.name):
            raw = await self._get(SEARCH_URL, params)
        places = [p for p in (_to_place(row) for row in (raw or [])) if p is not None]
        self._cache.set(key, places)
        log.info("geocoding.search", query=name, results=len(places))
        return places

    async def reverse(self, latitude: float, longitude: float) -> Place | None:
        key = f"r:{latitude:.3f}:{longitude:.3f}"
        cached = self._cache.get(key)
        if cached is not None:
            metrics.incr("geocoding.cache_hit", kind="reverse")
            return cached or None

        metrics.incr("geocoding.fetch", kind="reverse")
        with Timer("geocoding.reverse_latency", provider=self.name):
            raw = await self._get(
                REVERSE_URL,
                {
                    "lat": latitude,
                    "lon": longitude,
                    "format": "jsonv2",
                    "addressdetails": 1,
                    # Neighbourhood level: enough to say "you're at Bostanlı"
                    # without reading out a street address the angler did not
                    # ask to have spoken aloud.
                    "zoom": 14,
                },
            )
        place = _to_place(raw) if isinstance(raw, dict) else None
        self._cache.set(key, place)
        log.info("geocoding.reverse", coords=round_coords(latitude, longitude), found=bool(place))
        return place


def _to_place(row: Any) -> Place | None:
    if not isinstance(row, dict):
        return None
    try:
        latitude = float(row["lat"])
        longitude = float(row["lon"])
    except (KeyError, TypeError, ValueError):
        return None
    address = row.get("address") or {}
    display = row.get("display_name") or ""
    name = (
        row.get("name")
        or address.get("suburb")
        or address.get("neighbourhood")
        or address.get("village")
        or address.get("town")
        or address.get("city")
        or (display.split(",")[0] if display else "")
    )
    if not name:
        return None
    return Place(
        name=name,
        display_name=display or name,
        latitude=latitude,
        longitude=longitude,
        kind=row.get("type"),
        region=address.get("province") or address.get("state") or address.get("city"),
        country=address.get("country"),
        country_code=(address.get("country_code") or "").upper() or None,
    )


class MockGeocodingProvider:
    """Deterministic places for tests and Demo Mode.

    Carries the real Bostanlı problem — one coastal match near İzmir and
    several inland namesakes — so the ranking is exercised against the case
    that motivated it rather than against a tidy fixture.
    """

    name = "mock"

    _FIXTURES: dict[str, list[Place]] = {
        "bostanlı": [
            Place(
                "Bostanlı",
                "Bostanlı, Adıyaman",
                37.8133,
                38.8932,
                "village",
                "Adıyaman",
                "Türkiye",
                "TR",
            ),
            Place(
                "Bostanlı",
                "Bostanlı, Iğdır",
                40.0311,
                43.3768,
                "village",
                "Iğdır",
                "Türkiye",
                "TR",
            ),
            Place(
                "Bostanlı",
                "Bostanlı, Afyonkarahisar",
                38.9422,
                30.6364,
                "village",
                "Afyonkarahisar",
                "Türkiye",
                "TR",
            ),
            Place(
                "Bostanlı Mahallesi",
                "Bostanlı Mahallesi, Karşıyaka, İzmir",
                38.4570,
                27.0984,
                "administrative",
                "İzmir",
                "Türkiye",
                "TR",
            ),
        ],
        "kilyos": [
            Place(
                "Kumköy (Kilyos) Mahallesi",
                "Kumköy (Kilyos) Mahallesi, Sarıyer, İstanbul",
                41.2458,
                29.0365,
                "administrative",
                "İstanbul",
                "Türkiye",
                "TR",
            ),
        ],
        "nowhere": [],
    }

    async def search(self, name: str, country_code: str | None = None) -> list[Place]:
        return list(self._FIXTURES.get(name.strip().casefold(), []))

    async def reverse(self, latitude: float, longitude: float) -> Place | None:
        return Place(
            name="Bostanlı Mahallesi",
            display_name="Bostanlı Mahallesi, Karşıyaka, İzmir",
            latitude=latitude,
            longitude=longitude,
            kind="administrative",
            region="İzmir",
            country="Türkiye",
            country_code="TR",
        )


class NullGeocodingProvider:
    """Geocoding switched off. Everything degrades to "I don't know where"."""

    name = "none"

    async def search(self, name: str, country_code: str | None = None) -> list[Place]:
        return []

    async def reverse(self, latitude: float, longitude: float) -> Place | None:
        return None


_provider: GeocodingProvider | None = None


def get_geocoding_provider(settings: Settings | None = None) -> GeocodingProvider:
    global _provider
    if _provider is None:
        settings = settings or get_settings()
        if settings.geocoding_provider == "mock":
            _provider = MockGeocodingProvider()
        elif settings.geocoding_provider == "none":
            _provider = NullGeocodingProvider()
        else:
            _provider = NominatimProvider(settings)
    return _provider


def set_geocoding_provider(provider: GeocodingProvider | None) -> None:
    """Override the provider (tests, Demo Mode)."""
    global _provider
    _provider = provider
