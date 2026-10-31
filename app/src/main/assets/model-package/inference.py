"""Reference inference for TempLearn's portable JSON model format v2.

Standard-library only. See MODEL_FORMAT.md for the full input contract.
"""
from __future__ import annotations

from datetime import datetime
import json
import math
import sys
from pathlib import Path
from typing import Any, Mapping, Sequence
from zoneinfo import ZoneInfo

FORMAT_NAME = "templearn-weather-model"
FORMAT_VERSION = 2
EXPECTED_FEATURES = [
    "bias", "local_hour_sin", "local_hour_cos", "day_of_year_sin", "day_of_year_cos",
    "temperature_t1", "temperature_t2", "temperature_t3", "dew_point", "wind_speed",
    "wind_gust", "mean_sea_level_pressure", "relative_humidity", "cloud_cover_total",
    "precipitation", "shortwave_radiation", "temperature_trend_1", "temperature_trend_2",
    "solar_daylight_interaction", "cloud_daylight_interaction", "cloud_cover_low",
    "cloud_cover_mid", "cloud_cover_high", "wind_direction_sin", "wind_direction_cos",
    "vapour_pressure_deficit", "surface_pressure", "rain", "showers", "snowfall",
    "visibility", "evapotranspiration", "reference_et0", "uv_index", "uv_index_clear_sky",
    "wet_bulb_temperature", "apparent_temperature", "shortwave_radiation_instant",
    "weather_clear", "weather_partly_cloudy", "weather_fog", "weather_drizzle",
    "weather_rain", "weather_snow", "weather_rain_showers", "weather_thunderstorm",
    "latitude_normalized", "longitude_sin", "longitude_cos", "elevation_normalized",
    "elevation_present", "latitude_season_sin", "latitude_season_cos",
]


def _number(value: Any) -> float | None:
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _normalise(value: Any, center: float, scale: float) -> float:
    number = _number(value)
    if number is None:
        return 0.0
    return max(-4.0, min(4.0, (number - center) / scale))


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def build_feature_vector(
    target_timestamp_unix_seconds: int,
    previous_bom_hours: Sequence[Mapping[str, Any]],
    weather: Mapping[str, Any] | None = None,
    timezone_id: str = "UTC",
    location: Mapping[str, Any] | None = None,
) -> list[float]:
    """Build the exact 53-feature vector expected by a v2 model.

    Previous BOM records must be exactly three consecutive hourly observations, newest first.
    `location` must contain latitude and longitude; elevation_m is optional. Weather values must
    all be from the newest completed BOM observation and must not be substituted from another API.
    """
    if len(previous_bom_hours) != 3:
        raise ValueError("Exactly three consecutive previous BOM hourly observations are required.")
    records = list(previous_bom_hours)
    timestamps: list[int] = []
    temperatures: list[float] = []
    for record in records:
        try:
            timestamp = int(record["timestamp_unix_seconds"])
            temperature = _number(record["temperature_c"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError("Each BOM hour needs timestamp_unix_seconds and temperature_c.") from exc
        if temperature is None:
            raise ValueError("BOM temperature values must be finite numbers.")
        timestamps.append(timestamp)
        temperatures.append(temperature)

    target = int(target_timestamp_unix_seconds)
    if timestamps[0] != target - 3600:
        raise ValueError("The newest BOM observation must be exactly one hour before the target.")
    if timestamps[0] - timestamps[1] != 3600 or timestamps[1] - timestamps[2] != 3600:
        raise ValueError("Previous BOM observations must be three consecutive hourly samples.")

    try:
        local_time = datetime.fromtimestamp(target, ZoneInfo(timezone_id))
    except Exception as exc:
        raise ValueError(f"Invalid timezone: {timezone_id}") from exc

    location = location or {}
    latitude = _number(location.get("latitude"))
    longitude = _number(location.get("longitude"))
    elevation_m = _number(location.get("elevation_m"))
    if latitude is None or not -90.0 <= latitude <= 90.0:
        raise ValueError("A valid target-location latitude is required for a v2 model.")
    if longitude is None or not -180.0 <= longitude <= 180.0:
        raise ValueError("A valid target-location longitude is required for a v2 model.")

    weather = weather or {}
    t1, t2, t3 = temperatures
    hour_angle = 2.0 * math.pi * local_time.hour / 24.0
    days_in_year = 366 if _is_leap_year(local_time.year) else 365
    year_angle = 2.0 * math.pi * (local_time.timetuple().tm_yday - 1) / days_in_year
    daylight_factor = max(0.0, -math.cos(hour_angle))
    solar = _normalise(weather.get("solar_radiation_wm2"), 150.0, 300.0)
    cloud = _normalise(weather.get("cloud_cover_pct"), 50.0, 50.0)
    wind_direction = _number(weather.get("wind_direction_deg"))
    weather_code_value = _number(weather.get("weather_code"))
    weather_code = int(weather_code_value) if weather_code_value is not None else None
    direction_angle = math.radians(wind_direction or 0.0)
    latitude_normalized = _clamp(latitude / 90.0, -1.0, 1.0)

    features = [
        1.0,
        math.sin(hour_angle),
        math.cos(hour_angle),
        math.sin(year_angle),
        math.cos(year_angle),
        (t1 - 20.0) / 10.0,
        (t2 - 20.0) / 10.0,
        (t3 - 20.0) / 10.0,
        _normalise(weather.get("dew_point_c"), 20.0, 10.0),
        _normalise(weather.get("wind_speed_kmh"), 15.0, 15.0),
        _normalise(weather.get("wind_gust_kmh"), 25.0, 25.0),
        _normalise(weather.get("pressure_hpa"), 1013.0, 20.0),
        _normalise(weather.get("humidity_pct"), 50.0, 50.0),
        cloud,
        _normalise(weather.get("precipitation_mm"), 0.5, 2.0),
        solar,
        _clamp((t1 - t2) / 5.0, -4.0, 4.0),
        _clamp((t2 - t3) / 5.0, -4.0, 4.0),
        solar * daylight_factor,
        cloud * daylight_factor,
        _normalise(weather.get("cloud_low_pct"), 50.0, 50.0),
        _normalise(weather.get("cloud_mid_pct"), 50.0, 50.0),
        _normalise(weather.get("cloud_high_pct"), 50.0, 50.0),
        0.0 if wind_direction is None else math.sin(direction_angle),
        0.0 if wind_direction is None else math.cos(direction_angle),
        _normalise(weather.get("vapor_pressure_deficit_kpa"), 1.0, 1.0),
        _normalise(weather.get("surface_pressure_hpa"), 1000.0, 20.0),
        _normalise(weather.get("rain_mm"), 0.2, 1.0),
        _normalise(weather.get("showers_mm"), 0.1, 0.5),
        _normalise(weather.get("snowfall_cm"), 0.0, 1.0),
        _normalise(weather.get("visibility_m"), 10000.0, 10000.0),
        _normalise(weather.get("evapotranspiration_mm"), 0.1, 0.5),
        _normalise(weather.get("et0_mm"), 0.1, 0.5),
        _normalise(weather.get("uv_index"), 3.0, 3.0),
        _normalise(weather.get("uv_index_clear_sky"), 3.0, 3.0),
        _normalise(weather.get("wet_bulb_temperature_c"), 20.0, 10.0),
        _normalise(weather.get("apparent_temperature_c"), 20.0, 10.0),
        _normalise(weather.get("solar_radiation_instant_wm2"), 150.0, 300.0),
        1.0 if weather_code == 0 else 0.0,
        1.0 if weather_code is not None and 1 <= weather_code <= 3 else 0.0,
        1.0 if weather_code in (45, 48) else 0.0,
        1.0 if weather_code is not None and 51 <= weather_code <= 57 else 0.0,
        1.0 if weather_code is not None and 61 <= weather_code <= 67 else 0.0,
        1.0 if weather_code is not None and (71 <= weather_code <= 77 or weather_code in (85, 86)) else 0.0,
        1.0 if weather_code is not None and 80 <= weather_code <= 82 else 0.0,
        1.0 if weather_code is not None and 95 <= weather_code <= 99 else 0.0,
        latitude_normalized,
        math.sin(math.radians(longitude)),
        math.cos(math.radians(longitude)),
        _normalise(elevation_m, 500.0, 1000.0),
        0.0 if elevation_m is None else 1.0,
        latitude_normalized * math.sin(year_angle),
        latitude_normalized * math.cos(year_angle),
    ]
    if len(features) != len(EXPECTED_FEATURES):
        raise RuntimeError(f"Internal feature vector mismatch: {len(features)} != {len(EXPECTED_FEATURES)}")
    return features


def predict_next_hour(
    model: Mapping[str, Any],
    target_timestamp_unix_seconds: int,
    previous_bom_hours: Sequence[Mapping[str, Any]],
    weather: Mapping[str, Any] | None = None,
    timezone_id: str | None = None,
    location: Mapping[str, Any] | None = None,
) -> float:
    """Apply an exported v2 model and return next-hour air temperature in Celsius."""
    if model.get("format") != FORMAT_NAME or model.get("format_version") != FORMAT_VERSION:
        raise ValueError("Unsupported TempLearn model format/version.")
    if model.get("feature_count") != len(EXPECTED_FEATURES):
        raise ValueError(f"Model feature_count is not {len(EXPECTED_FEATURES)}.")
    schema = model.get("feature_schema")
    if not isinstance(schema, list) or len(schema) != len(EXPECTED_FEATURES):
        raise ValueError("Model feature_schema is missing or has the wrong length.")
    if any(not isinstance(item, Mapping) for item in schema):
        raise ValueError("Model feature_schema entries must be objects.")
    schema_names = [item.get("name") for item in schema]
    schema_indices = [item.get("index") for item in schema]
    if schema_names != EXPECTED_FEATURES or schema_indices != list(range(len(EXPECTED_FEATURES))):
        raise ValueError("Model feature_schema is missing, reordered, or incompatible.")
    weights = model.get("weights")
    if not isinstance(weights, list) or len(weights) != len(EXPECTED_FEATURES):
        raise ValueError(f"Model must contain exactly {len(EXPECTED_FEATURES)} weights.")
    numeric_weights = [_number(value) for value in weights]
    if any(value is None for value in numeric_weights):
        raise ValueError("Model contains a non-finite or invalid weight.")

    model_scope = model.get("model_scope", "location")
    selected_timezone = timezone_id or (None if model_scope == "shared" else model.get("timezone"))
    if not selected_timezone:
        raise ValueError("A target-location IANA timezone is required for a shared model.")
    selected_location = location or (None if model_scope == "shared" else model.get("default_location"))
    if not selected_location:
        raise ValueError("Target-location coordinates are required for a shared model.")

    vector = build_feature_vector(
        target_timestamp_unix_seconds=target_timestamp_unix_seconds,
        previous_bom_hours=previous_bom_hours,
        weather=weather,
        timezone_id=selected_timezone,
        location=selected_location,
    )
    normalised_prediction = sum(float(w) * x for w, x in zip(numeric_weights, vector))
    return _clamp(20.0 + 10.0 * normalised_prediction, -90.0, 70.0)


def _is_leap_year(year: int) -> bool:
    return year % 4 == 0 and (year % 100 != 0 or year % 400 == 0)


def _main() -> int:
    if len(sys.argv) != 3:
        print("Usage: python inference.py model.json request.json", file=sys.stderr)
        return 2
    model = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    request = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    prediction = predict_next_hour(
        model=model,
        target_timestamp_unix_seconds=int(request["target_timestamp_unix_seconds"]),
        previous_bom_hours=request["previous_bom_hours"],
        weather=request.get("weather"),
        timezone_id=request.get("timezone"),
        location=request.get("location"),
    )
    print(json.dumps({"prediction_c": prediction}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
