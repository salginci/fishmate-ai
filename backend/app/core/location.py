"""The one place the location-consent rule is written down.

Coarsening used to be three copies of `round(lat, 2)` in three modules. It is
a promise made to the user in Settings, so it must not be possible for one
copy to drift from the others.
"""

from __future__ import annotations

from app.db.models import LocationPrecision

#: Two decimal places is roughly a kilometre of latitude — a neighbourhood,
#: not a fishing spot. Anglers guard their spots, and this is the difference
#: between "near Bostanlı" and "on that rock".
_APPROXIMATE_DECIMALS = 2


def apply_location_precision(
    precision: str | None,
    latitude: float | None,
    longitude: float | None,
) -> tuple[float | None, float | None]:
    """Reduce coordinates to what the user consented to share.

    `none` drops them entirely; `approximate` (the default for a new account)
    coarsens to ~1 km; `precise` passes them through.
    """
    effective = precision or LocationPrecision.APPROXIMATE.value
    if effective == LocationPrecision.NONE.value:
        return None, None
    if latitude is None or longitude is None:
        return None, None
    if effective == LocationPrecision.APPROXIMATE.value:
        return (
            round(latitude, _APPROXIMATE_DECIMALS),
            round(longitude, _APPROXIMATE_DECIMALS),
        )
    return latitude, longitude
