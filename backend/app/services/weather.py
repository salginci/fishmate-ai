"""Weather, wind, sea-state and sun-time retrieval.

Provider choice
---------------
Open-Meteo is the default because it needs **no API key** (one less secret to
manage, and the Google demo cannot fail on a missing credential) and because it
publishes a *marine* endpoint with wave height, swell period and sea-surface
temperature — the variables that actually matter to a shore angler.

Two endpoints are combined into one `ConditionsSnapshot` so the model needs a
single tool call rather than three. The marine endpoint legitimately 400s for
inland coordinates; that is treated as "no sea data here", not an error.

Everything is behind `WeatherProvider` so a paid provider or a tide service can
be added without touching the tool layer.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import asdict, dataclass
from datetime import UTC, date, datetime, timedelta
from typing import Any, Protocol

import httpx

from app.config import Settings, get_settings
from app.core.errors import UpstreamUnavailableError
from app.core.observability import Timer, get_logger, metrics, round_coords

log = get_logger("weather")

FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
MARINE_URL = "https://marine-api.open-meteo.com/v1/marine"

# WMO weather interpretation codes -> short labels.
WMO_CODES: dict[int, tuple[str, str]] = {
    0: ("Açık", "Clear sky"),
    1: ("Çoğunlukla açık", "Mainly clear"),
    2: ("Parçalı bulutlu", "Partly cloudy"),
    3: ("Kapalı", "Overcast"),
    45: ("Puslu", "Fog"),
    48: ("Kırağılı pus", "Depositing rime fog"),
    51: ("Hafif çiseleme", "Light drizzle"),
    53: ("Çiseleme", "Moderate drizzle"),
    55: ("Yoğun çiseleme", "Dense drizzle"),
    61: ("Hafif yağmur", "Slight rain"),
    63: ("Yağmur", "Moderate rain"),
    65: ("Şiddetli yağmur", "Heavy rain"),
    66: ("Dondurucu yağmur", "Freezing rain"),
    67: ("Şiddetli dondurucu yağmur", "Heavy freezing rain"),
    71: ("Hafif kar", "Slight snow"),
    73: ("Kar", "Moderate snow"),
    75: ("Yoğun kar", "Heavy snow"),
    80: ("Hafif sağanak", "Slight rain showers"),
    81: ("Sağanak", "Moderate rain showers"),
    82: ("Şiddetli sağanak", "Violent rain showers"),
    95: ("Gök gürültülü fırtına", "Thunderstorm"),
    96: ("Dolulu fırtına", "Thunderstorm with slight hail"),
    99: ("Şiddetli dolulu fırtına", "Thunderstorm with heavy hail"),
}

_COMPASS_TR = (
    "K",
    "KKD",
    "KD",
    "DKD",
    "D",
    "DGD",
    "GD",
    "GGD",
    "G",
    "GGB",
    "GB",
    "BGB",
    "B",
    "BKB",
    "KB",
    "KKB",
)
_COMPASS_EN = (
    "N",
    "NNE",
    "NE",
    "ENE",
    "E",
    "ESE",
    "SE",
    "SSE",
    "S",
    "SSW",
    "SW",
    "WSW",
    "W",
    "WNW",
    "NW",
    "NNW",
)


def compass(degrees: float | None, language: str = "en") -> str | None:
    if degrees is None:
        return None
    table = _COMPASS_TR if language.startswith("tr") else _COMPASS_EN
    return table[int((degrees % 360) / 22.5 + 0.5) % 16]


def beaufort(kmh: float | None) -> int | None:
    """Wind force — anglers think in Beaufort, not km/h."""
    if kmh is None:
        return None
    bounds = (1, 5, 11, 19, 28, 38, 49, 61, 74, 88, 102, 117)
    for i, upper in enumerate(bounds):
        if kmh < upper:
            return i
    return 12


@dataclass
class ConditionsSnapshot:
    """One combined weather + sea-state observation."""

    observed_at: datetime
    latitude: float
    longitude: float
    provider: str
    timezone: str | None = None

    air_temp_c: float | None = None
    apparent_temp_c: float | None = None
    water_temp_c: float | None = None
    humidity_pct: float | None = None
    pressure_hpa: float | None = None
    cloud_cover_pct: float | None = None
    precipitation_mm: float | None = None
    weather_code: int | None = None
    is_day: bool | None = None

    wind_speed_kmh: float | None = None
    wind_gust_kmh: float | None = None
    wind_direction_deg: float | None = None

    wave_height_m: float | None = None
    wave_period_s: float | None = None
    wave_direction_deg: float | None = None
    swell_height_m: float | None = None
    swell_period_s: float | None = None

    #: Sunrise/sunset as returned by the provider: LOCAL wall-clock strings
    #: (timezone=auto), which is what an angler wants to read. Use
    #: `sunrise_utc()` / `sunset_utc()` for any arithmetic.
    sunrise: str | None = None
    sunset: str | None = None
    #: Offset of that local time from UTC. Without it, comparing a local
    #: sunrise against `datetime.now(UTC)` is wrong by the whole offset.
    utc_offset_seconds: int | None = None
    #: Set when the marine endpoint had no data for these coordinates.
    sea_data_available: bool = True

    def local_time(self) -> str | None:
        """Current wall-clock time at these coordinates, as HH:MM."""
        if self.utc_offset_seconds is None:
            return None
        local = datetime.now(UTC) + timedelta(seconds=self.utc_offset_seconds)
        return local.strftime("%H:%M")

    def _to_utc(self, local_iso: str | None) -> datetime | None:
        """Convert a provider local-time string to an aware UTC datetime."""
        if not local_iso:
            return None
        try:
            parsed = datetime.fromisoformat(local_iso)
        except ValueError:
            return None
        if parsed.tzinfo is not None:
            return parsed.astimezone(UTC)
        offset = self.utc_offset_seconds
        if offset is None:
            # No offset known: assume the string is already UTC rather than
            # silently shifting it by a guessed amount.
            return parsed.replace(tzinfo=UTC)
        return (parsed - timedelta(seconds=offset)).replace(tzinfo=UTC)

    def sunrise_utc(self) -> datetime | None:
        return self._to_utc(self.sunrise)

    def sunset_utc(self) -> datetime | None:
        return self._to_utc(self.sunset)

    def describe(self, language: str = "en") -> str:
        """Short human summary. Only mentions values we actually have."""
        tr = language.startswith("tr")
        bits: list[str] = []
        if self.weather_code is not None:
            labels = WMO_CODES.get(self.weather_code)
            if labels:
                bits.append(labels[0] if tr else labels[1])
        if self.air_temp_c is not None:
            bits.append(f"{self.air_temp_c:.0f}°C")
        if self.wind_speed_kmh is not None:
            d = compass(self.wind_direction_deg, language)
            bf = beaufort(self.wind_speed_kmh)
            wind = f"{d} {self.wind_speed_kmh:.0f} km/h" if d else f"{self.wind_speed_kmh:.0f} km/h"
            if bf is not None:
                wind += f" (Bf {bf})"
            bits.append(("rüzgâr " if tr else "wind ") + wind)
        if self.wave_height_m is not None:
            bits.append(
                f"dalga {self.wave_height_m:.1f} m" if tr else f"waves {self.wave_height_m:.1f} m"
            )
        if self.water_temp_c is not None:
            bits.append(
                f"su {self.water_temp_c:.0f}°C" if tr else f"water {self.water_temp_c:.0f}°C"
            )
        return ", ".join(bits) if bits else ("Veri yok" if tr else "No data")

    def to_tool_payload(self, language: str = "en") -> dict[str, Any]:
        """Shape handed back to Gemini as a functionResponse.

        Units are named in the keys so the model cannot misread them, and
        missing values are omitted entirely rather than sent as null — an
        absent key is much less likely to be narrated as a real measurement.
        """
        payload: dict[str, Any] = {
            "observed_at": self.observed_at.isoformat(),
            "summary": self.describe(language),
            "provider": self.provider,
        }
        optional = {
            "air_temperature_c": self.air_temp_c,
            "feels_like_c": self.apparent_temp_c,
            "water_temperature_c": self.water_temp_c,
            "humidity_pct": self.humidity_pct,
            "pressure_hpa": self.pressure_hpa,
            "cloud_cover_pct": self.cloud_cover_pct,
            "precipitation_mm": self.precipitation_mm,
            "wind_speed_kmh": self.wind_speed_kmh,
            "wind_gust_kmh": self.wind_gust_kmh,
            "wind_direction": compass(self.wind_direction_deg, language),
            "wind_beaufort": beaufort(self.wind_speed_kmh),
            "wave_height_m": self.wave_height_m,
            "wave_period_s": self.wave_period_s,
            "swell_height_m": self.swell_height_m,
            "swell_period_s": self.swell_period_s,
            "is_daylight": self.is_day,
            "sunrise_local": self.sunrise,
            "sunset_local": self.sunset,
            "timezone": self.timezone,
            # Wall-clock time *at the spot*, so the model can reason about dawn
            # and dusk without doing timezone arithmetic on an ISO string — and
            # so it is right when the angler asks about somewhere else.
            "local_time": self.local_time(),
        }
        payload.update({k: v for k, v in optional.items() if v is not None})
        if not self.sea_data_available:
            payload["note"] = (
                "No marine/sea-state data is available for these coordinates "
                "(likely an inland location)."
            )
        return payload

    def as_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["observed_at"] = self.observed_at.isoformat()
        return d


@dataclass
class HourlyPoint:
    time: str
    wind_speed_kmh: float | None = None
    wind_gust_kmh: float | None = None
    wind_direction_deg: float | None = None
    precipitation_mm: float | None = None
    wave_height_m: float | None = None
    air_temp_c: float | None = None


@dataclass
class SunTimes:
    date: str
    sunrise: str | None
    sunset: str | None
    timezone: str | None = None

    def to_tool_payload(self) -> dict[str, Any]:
        out: dict[str, Any] = {"date": self.date}
        if self.sunrise:
            out["sunrise"] = self.sunrise
        if self.sunset:
            out["sunset"] = self.sunset
        if self.timezone:
            out["timezone"] = self.timezone
        if not self.sunrise and not self.sunset:
            out["note"] = "Sun times unavailable for this location/date."
        return out


class WeatherProvider(Protocol):
    name: str

    async def current(self, lat: float, lon: float) -> ConditionsSnapshot: ...

    async def forecast(self, lat: float, lon: float, hours: int) -> list[HourlyPoint]: ...

    async def sun_times(self, lat: float, lon: float, on: date | None) -> SunTimes: ...


class _TTLCache:
    def __init__(self, ttl_seconds: int) -> None:
        self.ttl = ttl_seconds
        self._store: dict[str, tuple[float, Any]] = {}

    def get(self, key: str) -> Any | None:
        hit = self._store.get(key)
        if hit is None:
            return None
        stored_at, value = hit
        if time.monotonic() - stored_at > self.ttl:
            self._store.pop(key, None)
            return None
        return value

    def set(self, key: str, value: Any) -> None:
        if len(self._store) > 512:  # crude bound; coordinates are few in practice
            self._store.clear()
        self._store[key] = (time.monotonic(), value)

    def clear(self) -> None:
        self._store.clear()


class OpenMeteoProvider:
    """Open-Meteo forecast + marine endpoints. No API key required."""

    name = "open_meteo"

    _CURRENT_FIELDS = (
        "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,"
        "weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
    )
    _MARINE_CURRENT_FIELDS = (
        "wave_height,wave_direction,wave_period,swell_wave_height,swell_wave_period,"
        "sea_surface_temperature"
    )

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._cache = _TTLCache(self.settings.weather_cache_ttl_seconds)
        self._timeout = httpx.Timeout(self.settings.weather_timeout_seconds)

    # -- plumbing ---------------------------------------------------------
    async def _fetch(self, client: httpx.AsyncClient, url: str, params: dict) -> dict | None:
        """GET returning parsed JSON, or None when the endpoint has no data here.

        Open-Meteo answers 400 with a `reason` for out-of-domain coordinates
        (the marine grid excludes inland points). That is a legitimate "no
        data" answer, so it is logged at debug and folded into None, while
        transport failures propagate to the caller.
        """
        try:
            response = await client.get(url, params=params, timeout=self._timeout)
        except httpx.HTTPError as exc:
            log.warning("weather.transport_error", url=url, error=type(exc).__name__)
            raise UpstreamUnavailableError() from exc
        if response.status_code == 400:
            log.debug("weather.no_data_for_location", url=url)
            return None
        if response.status_code >= 500:
            metrics.incr("weather.upstream_5xx", url=url)
            raise UpstreamUnavailableError()
        if response.status_code != 200:
            log.warning("weather.unexpected_status", url=url, status=response.status_code)
            return None
        try:
            return response.json()
        except ValueError as exc:
            raise UpstreamUnavailableError() from exc

    # -- API --------------------------------------------------------------
    async def current(self, lat: float, lon: float) -> ConditionsSnapshot:
        cache_key = f"cur:{lat:.3f}:{lon:.3f}"
        cached = self._cache.get(cache_key)
        if cached is not None:
            metrics.incr("weather.cache_hit", kind="current")
            return cached

        metrics.incr("weather.fetch", kind="current")
        with Timer("weather.current_latency", provider=self.name):
            async with httpx.AsyncClient() as client:
                land_task = self._fetch(
                    client,
                    FORECAST_URL,
                    {
                        "latitude": lat,
                        "longitude": lon,
                        "current": self._CURRENT_FIELDS,
                        "daily": "sunrise,sunset",
                        "timezone": "auto",
                        "forecast_days": 1,
                    },
                )
                sea_task = self._fetch(
                    client,
                    MARINE_URL,
                    {
                        "latitude": lat,
                        "longitude": lon,
                        "current": self._MARINE_CURRENT_FIELDS,
                        "timezone": "auto",
                    },
                )
                # Sea data is optional, so a marine failure must not sink the
                # whole call — gather with return_exceptions and triage.
                gathered: list[Any] = list(
                    await asyncio.gather(land_task, sea_task, return_exceptions=True)
                )
                land, sea = gathered[0], gathered[1]

        if isinstance(land, BaseException):
            raise UpstreamUnavailableError() from land
        if land is None:
            raise UpstreamUnavailableError()
        # Separate names rather than reassigning `sea`: narrowing a
        # gather() union in place defeats type inference.
        sea_data: dict | None
        if isinstance(sea, BaseException):
            log.warning("weather.marine_failed", error=type(sea).__name__)
            sea_data = None
        else:
            sea_data = sea

        snapshot = self._build(lat, lon, land, sea_data)
        self._cache.set(cache_key, snapshot)
        log.info(
            "weather.current",
            coords=round_coords(lat, lon),
            summary=snapshot.describe("en"),
            sea=snapshot.sea_data_available,
        )
        return snapshot

    def _build(self, lat: float, lon: float, land: dict, sea: dict | None) -> ConditionsSnapshot:
        cur = land.get("current") or {}
        daily = land.get("daily") or {}
        sea_cur = (sea or {}).get("current") or {}

        def first(seq: Any) -> Any:
            return seq[0] if isinstance(seq, list) and seq else None

        observed_raw = cur.get("time")
        try:
            observed = datetime.fromisoformat(observed_raw) if observed_raw else datetime.now(UTC)
        except ValueError:
            observed = datetime.now(UTC)
        if observed.tzinfo is None:
            observed = observed.replace(tzinfo=UTC)

        is_day = cur.get("is_day")
        return ConditionsSnapshot(
            observed_at=observed,
            latitude=lat,
            longitude=lon,
            provider=self.name,
            timezone=land.get("timezone"),
            air_temp_c=cur.get("temperature_2m"),
            apparent_temp_c=cur.get("apparent_temperature"),
            humidity_pct=cur.get("relative_humidity_2m"),
            pressure_hpa=cur.get("pressure_msl"),
            cloud_cover_pct=cur.get("cloud_cover"),
            precipitation_mm=cur.get("precipitation"),
            weather_code=cur.get("weather_code"),
            is_day=None if is_day is None else bool(is_day),
            wind_speed_kmh=cur.get("wind_speed_10m"),
            wind_gust_kmh=cur.get("wind_gusts_10m"),
            wind_direction_deg=cur.get("wind_direction_10m"),
            water_temp_c=sea_cur.get("sea_surface_temperature"),
            wave_height_m=sea_cur.get("wave_height"),
            wave_period_s=sea_cur.get("wave_period"),
            wave_direction_deg=sea_cur.get("wave_direction"),
            swell_height_m=sea_cur.get("swell_wave_height"),
            swell_period_s=sea_cur.get("swell_wave_period"),
            sunrise=first(daily.get("sunrise")),
            sunset=first(daily.get("sunset")),
            utc_offset_seconds=land.get("utc_offset_seconds"),
            # The marine endpoint echoes `time`/`interval` even for inland
            # points, so presence of the dict proves nothing — require at
            # least one real measurement.
            sea_data_available=any(
                sea_cur.get(k) is not None
                for k in (
                    "wave_height",
                    "wave_period",
                    "swell_wave_height",
                    "sea_surface_temperature",
                )
            ),
        )

    async def forecast(self, lat: float, lon: float, hours: int) -> list[HourlyPoint]:
        hours = max(1, min(hours, 24))
        cache_key = f"fc:{lat:.3f}:{lon:.3f}:{hours}"
        cached = self._cache.get(cache_key)
        if cached is not None:
            metrics.incr("weather.cache_hit", kind="forecast")
            return cached

        metrics.incr("weather.fetch", kind="forecast")
        async with httpx.AsyncClient() as client:
            land_task = self._fetch(
                client,
                FORECAST_URL,
                {
                    "latitude": lat,
                    "longitude": lon,
                    "hourly": (
                        "temperature_2m,precipitation,wind_speed_10m,"
                        "wind_gusts_10m,wind_direction_10m"
                    ),
                    "timezone": "auto",
                    "forecast_days": 2,
                },
            )
            sea_task = self._fetch(
                client,
                MARINE_URL,
                {
                    "latitude": lat,
                    "longitude": lon,
                    "hourly": "wave_height",
                    "timezone": "auto",
                    "forecast_days": 2,
                },
            )
            gathered: list[Any] = list(
                await asyncio.gather(land_task, sea_task, return_exceptions=True)
            )
            land, sea = gathered[0], gathered[1]

        if isinstance(land, BaseException) or land is None:
            raise UpstreamUnavailableError()
        sea_data: dict | None = None if isinstance(sea, BaseException) else sea

        hourly: dict = land.get("hourly") or {}
        times: list[str] = hourly.get("time") or []
        sea_hourly: dict = (sea_data or {}).get("hourly") or {}
        waves = sea_hourly.get("wave_height") or []
        wave_times = sea_hourly.get("time") or []
        wave_by_time = dict(zip(wave_times, waves, strict=False))

        # Start from the current hour rather than midnight.
        now_hour = datetime.now().strftime("%Y-%m-%dT%H:00")
        try:
            start = times.index(now_hour)
        except ValueError:
            start = 0

        def at(seq_name: str, i: int) -> float | None:
            seq = hourly.get(seq_name) or []
            return seq[i] if i < len(seq) else None

        points: list[HourlyPoint] = []
        for i in range(start, min(start + hours, len(times))):
            points.append(
                HourlyPoint(
                    time=times[i],
                    air_temp_c=at("temperature_2m", i),
                    precipitation_mm=at("precipitation", i),
                    wind_speed_kmh=at("wind_speed_10m", i),
                    wind_gust_kmh=at("wind_gusts_10m", i),
                    wind_direction_deg=at("wind_direction_10m", i),
                    wave_height_m=wave_by_time.get(times[i]),
                )
            )
        self._cache.set(cache_key, points)
        return points

    async def sun_times(self, lat: float, lon: float, on: date | None = None) -> SunTimes:
        target = on or date.today()
        cache_key = f"sun:{lat:.3f}:{lon:.3f}:{target.isoformat()}"
        cached = self._cache.get(cache_key)
        if cached is not None:
            return cached

        async with httpx.AsyncClient() as client:
            data = await self._fetch(
                client,
                FORECAST_URL,
                {
                    "latitude": lat,
                    "longitude": lon,
                    "daily": "sunrise,sunset",
                    "timezone": "auto",
                    "start_date": target.isoformat(),
                    "end_date": target.isoformat(),
                },
            )
        if data is None:
            raise UpstreamUnavailableError()
        daily = data.get("daily") or {}

        def first(key: str) -> str | None:
            seq = daily.get(key)
            return seq[0] if isinstance(seq, list) and seq else None

        result = SunTimes(
            date=target.isoformat(),
            sunrise=first("sunrise"),
            sunset=first("sunset"),
            timezone=data.get("timezone"),
        )
        self._cache.set(cache_key, result)
        return result


class MockWeatherProvider:
    """Deterministic conditions for tests and Demo Mode.

    Values are derived from the coordinates so they are stable across runs but
    differ between spots. Demo Mode uses this so a presentation never depends
    on live network conditions (docs/DEMO.md).
    """

    name = "mock"

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    def _seed(self, lat: float, lon: float) -> float:
        return abs(lat * 37.0 + lon * 11.0) % 1.0

    async def current(self, lat: float, lon: float) -> ConditionsSnapshot:
        s = self._seed(lat, lon)
        return ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=lat,
            longitude=lon,
            provider=self.name,
            timezone="Europe/Istanbul",
            air_temp_c=round(16 + s * 8, 1),
            apparent_temp_c=round(15 + s * 8, 1),
            water_temp_c=round(19 + s * 4, 1),
            humidity_pct=round(55 + s * 25),
            pressure_hpa=round(1012 + s * 10, 1),
            cloud_cover_pct=round(s * 100),
            precipitation_mm=0.0,
            weather_code=2 if s < 0.5 else 3,
            is_day=True,
            wind_speed_kmh=round(8 + s * 14, 1),
            wind_gust_kmh=round(14 + s * 18, 1),
            wind_direction_deg=round(200 + s * 120),
            wave_height_m=round(0.2 + s * 0.6, 2),
            wave_period_s=round(3 + s * 3, 2),
            wave_direction_deg=round(190 + s * 140),
            swell_height_m=round(0.15 + s * 0.4, 2),
            swell_period_s=round(2.5 + s * 3, 2),
            sunrise=f"{date.today().isoformat()}T07:12",
            sunset=f"{date.today().isoformat()}T18:51",
            utc_offset_seconds=10_800,  # Europe/Istanbul, matching the demo spot
            sea_data_available=True,
        )

    async def forecast(self, lat: float, lon: float, hours: int) -> list[HourlyPoint]:
        s = self._seed(lat, lon)
        now = datetime.now()
        points = []
        for i in range(max(1, min(hours, 24))):
            points.append(
                HourlyPoint(
                    time=now.replace(minute=0, second=0, microsecond=0).isoformat(
                        timespec="minutes"
                    ),
                    air_temp_c=round(16 + s * 8 - i * 0.3, 1),
                    precipitation_mm=0.0,
                    wind_speed_kmh=round(8 + s * 14 + i * 0.8, 1),
                    wind_gust_kmh=round(14 + s * 18 + i, 1),
                    wind_direction_deg=round(200 + s * 120),
                    wave_height_m=round(0.2 + s * 0.6 + i * 0.02, 2),
                )
            )
        return points

    async def sun_times(self, lat: float, lon: float, on: date | None = None) -> SunTimes:
        target = on or date.today()
        return SunTimes(
            date=target.isoformat(),
            sunrise=f"{target.isoformat()}T07:12",
            sunset=f"{target.isoformat()}T18:51",
            timezone="Europe/Istanbul",
        )


_provider: WeatherProvider | None = None


def get_weather_provider(settings: Settings | None = None) -> WeatherProvider:
    global _provider
    if _provider is None:
        settings = settings or get_settings()
        _provider = (
            MockWeatherProvider(settings)
            if settings.weather_provider == "mock"
            else OpenMeteoProvider(settings)
        )
    return _provider


def set_weather_provider(provider: WeatherProvider | None) -> None:
    """Override the provider (tests, Demo Mode)."""
    global _provider
    _provider = provider
