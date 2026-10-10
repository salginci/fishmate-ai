"""Weather/marine provider: parsing, graceful degradation, no fabrication."""

from __future__ import annotations

from datetime import UTC, datetime

import httpx
import pytest
import respx

from app.config import Settings
from app.core.errors import UpstreamUnavailableError
from app.services.weather import (
    FORECAST_URL,
    MARINE_URL,
    ConditionsSnapshot,
    MockWeatherProvider,
    OpenMeteoProvider,
    beaufort,
    compass,
)

FORECAST_BODY = {
    "timezone": "Europe/Istanbul",
    "current": {
        "time": "2026-10-07T09:45",
        "temperature_2m": 17.4,
        "relative_humidity_2m": 62,
        "apparent_temperature": 16.8,
        "is_day": 1,
        "precipitation": 0.0,
        "weather_code": 3,
        "cloud_cover": 100,
        "pressure_msl": 1019.4,
        "wind_speed_10m": 14.0,
        "wind_direction_10m": 275,
        "wind_gusts_10m": 22.0,
    },
    "daily": {"sunrise": ["2026-10-07T07:12"], "sunset": ["2026-10-07T18:45"]},
}

MARINE_BODY = {
    "timezone": "Europe/Istanbul",
    "current": {
        "time": "2026-10-07T09:45",
        "wave_height": 0.42,
        "wave_direction": 337,
        "wave_period": 2.75,
        "swell_wave_height": 0.3,
        "swell_wave_period": 2.25,
        "sea_surface_temperature": 21.7,
    },
}

#: What Open-Meteo's marine endpoint returns for an inland point: the envelope
#: is present but every measurement is null.
MARINE_BODY_INLAND = {
    "timezone": "Europe/Istanbul",
    "current": {
        "time": "2026-10-07T09:45",
        "wave_height": None,
        "wave_direction": None,
        "wave_period": None,
        "swell_wave_height": None,
        "swell_wave_period": None,
        "sea_surface_temperature": None,
    },
}


def provider() -> OpenMeteoProvider:
    # A fresh instance per test so the TTL cache never leaks across tests.
    return OpenMeteoProvider(Settings(auth_secret="w" * 48, weather_cache_ttl_seconds=0))


class TestHelpers:
    @pytest.mark.parametrize(
        ("degrees", "tr", "en"),
        [(0, "K", "N"), (90, "D", "E"), (180, "G", "S"), (270, "B", "W"), (275, "B", "W")],
    )
    def test_compass(self, degrees, tr, en):
        assert compass(degrees, "tr") == tr
        assert compass(degrees, "en") == en

    def test_compass_handles_none_and_wraparound(self):
        assert compass(None) is None
        assert compass(360, "en") == "N"

    @pytest.mark.parametrize(
        ("kmh", "force"), [(0, 0), (3, 1), (15, 3), (35, 5), (70, 8), (200, 12)]
    )
    def test_beaufort(self, kmh, force):
        assert beaufort(kmh) == force

    def test_beaufort_none(self):
        assert beaufort(None) is None


class TestOpenMeteo:
    @respx.mock
    async def test_combines_land_and_sea(self):
        respx.get(FORECAST_URL).mock(return_value=httpx.Response(200, json=FORECAST_BODY))
        respx.get(MARINE_URL).mock(return_value=httpx.Response(200, json=MARINE_BODY))

        snapshot = await provider().current(38.46, 27.09)
        assert snapshot.air_temp_c == 17.4
        assert snapshot.wind_speed_kmh == 14.0
        assert snapshot.wave_height_m == 0.42
        assert snapshot.water_temp_c == 21.7
        assert snapshot.sunrise == "2026-10-07T07:12"
        assert snapshot.sea_data_available is True

    @respx.mock
    async def test_summary_is_localised(self):
        respx.get(FORECAST_URL).mock(return_value=httpx.Response(200, json=FORECAST_BODY))
        respx.get(MARINE_URL).mock(return_value=httpx.Response(200, json=MARINE_BODY))
        snapshot = await provider().current(38.46, 27.09)
        assert "rüzgâr" in snapshot.describe("tr")
        assert "wind" in snapshot.describe("en")
        assert "Bf 3" in snapshot.describe("tr")

    @respx.mock
    async def test_inland_null_measurements_flagged_as_no_sea_data(self):
        """The envelope being present must not be mistaken for real data."""
        respx.get(FORECAST_URL).mock(return_value=httpx.Response(200, json=FORECAST_BODY))
        respx.get(MARINE_URL).mock(return_value=httpx.Response(200, json=MARINE_BODY_INLAND))
        snapshot = await provider().current(39.93, 32.86)
        assert snapshot.sea_data_available is False
        payload = snapshot.to_tool_payload("en")
        assert "No marine" in payload["note"]
        # Absent, not null: a missing key cannot be narrated as a measurement.
        assert "wave_height_m" not in payload

    @respx.mock
    async def test_marine_400_is_not_an_error(self):
        respx.get(FORECAST_URL).mock(return_value=httpx.Response(200, json=FORECAST_BODY))
        respx.get(MARINE_URL).mock(
            return_value=httpx.Response(400, json={"reason": "out of bounds"})
        )
        snapshot = await provider().current(39.93, 32.86)
        assert snapshot.air_temp_c == 17.4
        assert snapshot.sea_data_available is False

    @respx.mock
    async def test_marine_failure_does_not_sink_the_call(self):
        respx.get(FORECAST_URL).mock(return_value=httpx.Response(200, json=FORECAST_BODY))
        respx.get(MARINE_URL).mock(side_effect=httpx.ConnectError("boom"))
        snapshot = await provider().current(38.46, 27.09)
        assert snapshot.air_temp_c == 17.4
        assert snapshot.sea_data_available is False

    @respx.mock
    async def test_land_failure_raises_upstream_error(self):
        """We must fail loudly rather than return invented weather."""
        respx.get(FORECAST_URL).mock(side_effect=httpx.ConnectError("offline"))
        respx.get(MARINE_URL).mock(side_effect=httpx.ConnectError("offline"))
        with pytest.raises(UpstreamUnavailableError):
            await provider().current(38.46, 27.09)

    @respx.mock
    async def test_land_5xx_raises(self):
        respx.get(FORECAST_URL).mock(return_value=httpx.Response(503))
        respx.get(MARINE_URL).mock(return_value=httpx.Response(200, json=MARINE_BODY))
        with pytest.raises(UpstreamUnavailableError):
            await provider().current(38.46, 27.09)

    @respx.mock
    async def test_caching_avoids_duplicate_requests(self):
        route = respx.get(FORECAST_URL).mock(return_value=httpx.Response(200, json=FORECAST_BODY))
        respx.get(MARINE_URL).mock(return_value=httpx.Response(200, json=MARINE_BODY))
        cached = OpenMeteoProvider(Settings(auth_secret="w" * 48, weather_cache_ttl_seconds=600))
        await cached.current(38.46, 27.09)
        await cached.current(38.46, 27.09)
        assert route.call_count == 1

    @respx.mock
    async def test_forecast_clamped_to_24_hours(self):
        hourly = {
            "time": [f"2026-10-07T{h:02d}:00" for h in range(24)],
            "temperature_2m": [17.0] * 24,
            "precipitation": [0.0] * 24,
            "wind_speed_10m": [12.0] * 24,
            "wind_gusts_10m": [20.0] * 24,
            "wind_direction_10m": [270] * 24,
        }
        respx.get(FORECAST_URL).mock(return_value=httpx.Response(200, json={"hourly": hourly}))
        respx.get(MARINE_URL).mock(
            return_value=httpx.Response(
                200, json={"hourly": {"time": hourly["time"], "wave_height": [0.4] * 24}}
            )
        )
        points = await provider().forecast(38.46, 27.09, 999)
        assert 0 < len(points) <= 24

    @respx.mock
    async def test_sun_times(self):
        respx.get(FORECAST_URL).mock(
            return_value=httpx.Response(
                200,
                json={
                    "timezone": "Europe/Istanbul",
                    "daily": {
                        "sunrise": ["2026-10-07T07:12"],
                        "sunset": ["2026-10-07T18:45"],
                    },
                },
            )
        )
        times = await provider().sun_times(38.46, 27.09)
        payload = times.to_tool_payload()
        assert payload["sunrise"] == "2026-10-07T07:12"
        assert payload["sunset"] == "2026-10-07T18:45"


class TestMockProvider:
    async def test_deterministic_for_the_same_spot(self):
        """Demo Mode depends on this: identical values in rehearsal and on stage."""
        first = await MockWeatherProvider().current(38.46, 27.09)
        second = await MockWeatherProvider().current(38.46, 27.09)
        assert first.describe("tr") == second.describe("tr")

    async def test_differs_between_spots(self):
        a = await MockWeatherProvider().current(38.46, 27.09)
        b = await MockWeatherProvider().current(41.01, 28.98)
        assert a.describe("tr") != b.describe("tr")

    async def test_provides_sea_data(self):
        snapshot = await MockWeatherProvider().current(38.46, 27.09)
        assert snapshot.wave_height_m is not None
        assert snapshot.water_temp_c is not None


class TestSnapshotPayload:
    def test_omits_missing_values_entirely(self):
        bare = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=0.0,
            longitude=0.0,
            provider="t",
            sea_data_available=False,
        )
        payload = bare.to_tool_payload("en")
        assert "air_temperature_c" not in payload
        assert "wind_speed_kmh" not in payload
        assert payload["summary"] == "No data"

    def test_includes_derived_wind_fields(self):
        snapshot = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=0.0,
            longitude=0.0,
            provider="t",
            wind_speed_kmh=35.0,
            wind_direction_deg=180.0,
        )
        payload = snapshot.to_tool_payload("en")
        assert payload["wind_direction"] == "S"
        assert payload["wind_beaufort"] == 5


class TestSunTimeTimezones:
    """Regression: provider sunrise/sunset are LOCAL, not UTC.

    Treating them as UTC made the recommendation engine report "close to
    sunrise" in the middle of the afternoon for any location with a non-zero
    UTC offset — and the dusk/dawn logic drives most of the advice.
    """

    def test_local_time_converted_using_offset(self):
        snapshot = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=38.46,
            longitude=27.09,
            provider="open_meteo",
            sunrise="2026-10-07T07:12",
            sunset="2026-10-07T18:45",
            utc_offset_seconds=10_800,  # Europe/Istanbul
        )
        assert snapshot.sunrise_utc().isoformat() == "2026-10-07T04:12:00+00:00"
        assert snapshot.sunset_utc().isoformat() == "2026-10-07T15:45:00+00:00"

    def test_negative_offset(self):
        snapshot = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=40.7,
            longitude=-74.0,
            provider="open_meteo",
            sunrise="2026-10-07T06:55",
            utc_offset_seconds=-14_400,  # America/New_York, DST
        )
        assert snapshot.sunrise_utc().isoformat() == "2026-10-07T10:55:00+00:00"

    def test_missing_offset_assumes_utc_rather_than_guessing(self):
        snapshot = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=0.0,
            longitude=0.0,
            provider="t",
            sunrise="2026-10-07T06:00",
            utc_offset_seconds=None,
        )
        assert snapshot.sunrise_utc().isoformat() == "2026-10-07T06:00:00+00:00"

    def test_malformed_and_absent_values(self):
        snapshot = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=0.0,
            longitude=0.0,
            provider="t",
            sunrise="not-a-time",
            sunset=None,
            utc_offset_seconds=0,
        )
        assert snapshot.sunrise_utc() is None
        assert snapshot.sunset_utc() is None

    @respx.mock
    async def test_offset_captured_from_the_api(self):
        body = dict(FORECAST_BODY, utc_offset_seconds=10_800)
        respx.get(FORECAST_URL).mock(return_value=httpx.Response(200, json=body))
        respx.get(MARINE_URL).mock(return_value=httpx.Response(200, json=MARINE_BODY))
        snapshot = await provider().current(38.46, 27.09)
        assert snapshot.utc_offset_seconds == 10_800
        assert snapshot.sunrise_utc().hour == 4  # 07:12 local -> 04:12 UTC

    def test_payload_labels_times_as_local(self):
        """The model must not read a local time as UTC either."""
        snapshot = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=38.46,
            longitude=27.09,
            provider="t",
            sunrise="2026-10-07T07:12",
            sunset="2026-10-07T18:45",
            utc_offset_seconds=10_800,
            timezone="Europe/Istanbul",
        )
        payload = snapshot.to_tool_payload("en")
        assert payload["sunrise_local"] == "2026-10-07T07:12"
        assert payload["timezone"] == "Europe/Istanbul"
