# TempLearn portable model package — format version 2

TempLearn exports a ZIP bundle intended to be loaded by another application without TempLearn's Android app, local SQLite database, or API credentials. The bundle includes the complete inference state and reference code for inference and retraining.

## Package contents

- `model.json`: model architecture, fitted coefficients, ordered feature schema, preprocessing, source policy, saved location profiles, training coverage and validation results. `training.contributing_location_count` is the actual number used for fitting.
- `feature_schema.json`: machine-readable ordered feature schema.
- `inference.py`: standard-library Python inference implementation and CLI.
- `trainer.py`: standard-library reference ridge-regression trainer for new BOM JSONL data.
- `inference_contract.json`: inference requirements and provenance rules.
- `training_metadata.json` and `evaluation.json`: training coverage and held-out evaluation.
- `MODEL_CARD.md`: human-readable summary, intended use and limitations.
- `README.md` and `requirements.txt`.

## Compatibility

- `format`: `templearn-weather-model`
- `format_version`: `2`
- `feature_count`: exactly `53`
- `weights`: exactly 53 finite floating-point values, in the exact order of `feature_schema`
- `feature_schema`: 53 objects with sequential `index` values 0–52 and the feature `name`
- `coefficients`: the same weights repeated as named/indexed entries

Reject unknown format versions, missing/reordered features, or any coefficient count other than 53. Do not assume later versions preserve v2 feature ordering.

## Inference contract

Supply:

1. `target_timestamp_unix_seconds`: Unix timestamp of the target local top-of-hour prediction.
2. Three consecutive BOM hourly observations, newest first (`t1`, `t2`, `t3`). `t1` must be exactly 3,600 seconds before the target. Earlier observations must each be 3,600 seconds apart.
3. Weather variables for the latest completed hour (`t1`), all originating from that same BOM station observation. Missing fields may be omitted. Never fill them with another provider.
4. The target location's IANA timezone.
5. Geographic context: latitude and longitude in WGS84 degrees, plus optional elevation above mean sea level in metres.

A location-specific model includes a `default_location`, so its coordinates, elevation and timezone can be used when the request omits them. A shared model must be given the target location's coordinates and timezone explicitly. Elevation is optional; when missing, the model uses the documented mean-imputation value and an explicit `elevation_present=0` feature.

Example request JSON:

```json
{
  "target_timestamp_unix_seconds": 1800003600,
  "timezone": "Australia/Sydney",
  "location": {"latitude": -33.7198, "longitude": 150.3074, "elevation_m": 1000},
  "previous_bom_hours": [
    {"timestamp_unix_seconds": 1800000000, "temperature_c": 21.8},
    {"timestamp_unix_seconds": 1799996400, "temperature_c": 21.2},
    {"timestamp_unix_seconds": 1799992800, "temperature_c": 20.7}
  ],
  "weather": {"humidity_pct": 55, "wind_speed_kmh": 12, "weather_code": 2}
}
```

Run `python inference.py model.json request.json`. The script prints `{"prediction_c": ...}`. No network access or third-party Python package is required.

## Input weather keys and units

All keys are optional. Every supplied historic weather value must come from BOM for the same station/hour as `t1`.

| Input key | Unit |
|---|---|
| `dew_point_c`, `wet_bulb_temperature_c`, `apparent_temperature_c` | °C |
| `wind_speed_kmh`, `wind_gust_kmh` | km/h |
| `pressure_hpa`, `surface_pressure_hpa` | hPa |
| `humidity_pct`, `cloud_cover_pct`, `cloud_low_pct`, `cloud_mid_pct`, `cloud_high_pct` | % |
| `precipitation_mm`, `rain_mm`, `showers_mm` | mm |
| `solar_radiation_wm2`, `solar_radiation_instant_wm2` | W/m² |
| `wind_direction_deg` | Degrees clockwise from north, 0–360 |
| `vapor_pressure_deficit_kpa` | kPa |
| `snowfall_cm` | cm |
| `visibility_m` | m |
| `evapotranspiration_mm`, `et0_mm` | mm |
| `uv_index`, `uv_index_clear_sky` | Index |
| `weather_code` | WMO code integer |

Coordinates are in degrees; elevation is metres above mean sea level.

## Feature order and transforms

Let `norm(x,c,s) = clamp((x-c)/s,-4,4)`; missing/non-finite numeric values become zero. Let `h` be the target's local hour, `d` its day of year, `D` the number of days in that year, and `t1`, `t2`, `t3` the three previous BOM temperatures, newest first. Let `solar = norm(solar_radiation_wm2,150,300)`, `cloud = norm(cloud_cover_pct,50,50)`, and `daylight = max(0,-cos(2πh/24))`. Let `lat = clamp(latitude/90,-1,1)` and longitude be converted to radians.

| # | Feature | Formula |
|---:|---|---|
| 0 | `bias` | `1` |
| 1 | `local_hour_sin` | `sin(2πh/24)` |
| 2 | `local_hour_cos` | `cos(2πh/24)` |
| 3 | `day_of_year_sin` | `sin(2π(d-1)/D)` |
| 4 | `day_of_year_cos` | `cos(2π(d-1)/D)` |
| 5–7 | `temperature_t1`, `temperature_t2`, `temperature_t3` | `(t-20)/10` |
| 8 | `dew_point` | `norm(dew_point_c,20,10)` |
| 9 | `wind_speed` | `norm(wind_speed_kmh,15,15)` |
| 10 | `wind_gust` | `norm(wind_gust_kmh,25,25)` |
| 11 | `mean_sea_level_pressure` | `norm(pressure_hpa,1013,20)` |
| 12 | `relative_humidity` | `norm(humidity_pct,50,50)` |
| 13 | `cloud_cover_total` | `cloud` |
| 14 | `precipitation` | `norm(precipitation_mm,0.5,2)` |
| 15 | `shortwave_radiation` | `solar` |
| 16–17 | `temperature_trend_1`, `temperature_trend_2` | `clamp((t1-t2)/5,-4,4)`, `clamp((t2-t3)/5,-4,4)` |
| 18–19 | `solar_daylight_interaction`, `cloud_daylight_interaction` | `solar*daylight`, `cloud*daylight` |
| 20–22 | `cloud_cover_low`, `cloud_cover_mid`, `cloud_cover_high` | `norm(cloud_layer_pct,50,50)` |
| 23–24 | `wind_direction_sin`, `wind_direction_cos` | `sin(direction_rad)`, `cos(direction_rad)`; both zero if missing |
| 25 | `vapour_pressure_deficit` | `norm(vapor_pressure_deficit_kpa,1,1)` |
| 26 | `surface_pressure` | `norm(surface_pressure_hpa,1000,20)` |
| 27 | `rain` | `norm(rain_mm,0.2,1)` |
| 28 | `showers` | `norm(showers_mm,0.1,0.5)` |
| 29 | `snowfall` | `norm(snowfall_cm,0,1)` |
| 30 | `visibility` | `norm(visibility_m,10000,10000)` |
| 31 | `evapotranspiration` | `norm(evapotranspiration_mm,0.1,0.5)` |
| 32 | `reference_et0` | `norm(et0_mm,0.1,0.5)` |
| 33 | `uv_index` | `norm(uv_index,3,3)` |
| 34 | `uv_index_clear_sky` | `norm(uv_index_clear_sky,3,3)` |
| 35 | `wet_bulb_temperature` | `norm(wet_bulb_temperature_c,20,10)` |
| 36 | `apparent_temperature` | `norm(apparent_temperature_c,20,10)` |
| 37 | `shortwave_radiation_instant` | `norm(solar_radiation_instant_wm2,150,300)` |
| 38–45 | WMO condition flags | One-hot flags for clear, partly cloudy, fog, drizzle, rain, snow, rain showers, thunderstorm |
| 46 | `latitude_normalized` | `lat` |
| 47 | `longitude_sin` | `sin(longitude_rad)` |
| 48 | `longitude_cos` | `cos(longitude_rad)` |
| 49 | `elevation_normalized` | `norm(elevation_m,500,1000)` |
| 50 | `elevation_present` | `1` if elevation is known, else `0` |
| 51 | `latitude_season_sin` | `lat*sin(year_angle)` |
| 52 | `latitude_season_cos` | `lat*cos(year_angle)` |

The output is `clamp(20 + 10 * dot(weights, features), -90, 70)` Celsius.

## Training implementation

`trainer.py` accepts JSON Lines with one BOM observation per line. Each row has `location_key`, `timestamp_unix_seconds`, `temperature_c`, `temperature_source` (must explicitly be `BOM`), `weather_source` (must explicitly be `BOM`), `timezone`, `latitude`, `longitude`, optional `elevation_m`, and optional `weather` object containing the keys above. The source fields are required even when the weather object is empty; the trainer rejects unlabelled or mixed-provider rows. Rows for each location are sorted by timestamp; incomplete/nonconsecutive hour sequences are skipped. The last 20% per location is held out chronologically. The trainer applies the same feature builder and ridge-regression gradient-descent configuration documented in `model.json` and writes an updated model JSON.

Example command: `python trainer.py model.json bom_training.jsonl updated_model.json`.

## Provider boundaries

- **BOM:** exclusive source for historical hourly air-temperature targets and historical hourly predictors used by the model. Missing observations remain missing.
- **Open-Meteo Weather:** previous completed local-day non-temperature aggregates only (`past_days=1`, `forecast_days=0`). Stored separately and not currently model features.
- **Open-Meteo Geocoding:** place identity, coordinates, elevation and timezone; these are geographic features, not weather observations.
- **Open-Meteo Elevation:** fallback terrain elevation using the 90 m global DEM if the geocoder supplies no elevation.
- **Visual Crossing:** not queried in TempLearn 0.7.0.

## Evaluation caveat

Validation uses a chronological held-out portion of stored examples, separately for each contributing location. It is a useful diagnostic, not a guarantee of future forecast accuracy. Station representativeness, data gaps, unseen weather regimes and the distribution of training locations all affect performance. TempLearn 0.7.0's historical target source is BOM, whose observation network is Australian; geocoding a place elsewhere does not create temperature observations for it.
