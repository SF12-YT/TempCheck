# TempLearn portable model package — format version 3

TempLearn exports a ZIP bundle for use by another application without requiring the Android app, its SQLite database, API credentials or network access for inference.

## Package contents

- `model.json`: architecture, fitted coefficients, ordered feature schema, preprocessing, source policy, saved locations, training coverage and validation results.
- `feature_schema.json`: machine-readable ordered 62-feature schema.
- `inference.py`: standard-library Python inference implementation and CLI.
- `trainer.py`: standard-library ridge-regression trainer for Open-Meteo Best Match JSONL data.
- `inference_contract.json`, `training_metadata.json`, `evaluation.json`, `MODEL_CARD.md`, `README.md` and `requirements.txt`.

## Compatibility

| Property | Required value |
|---|---|
| `format` | `templearn-weather-model` |
| `format_version` | `3` |
| `feature_count` | `62` |
| `weights` | 62 finite floats in exact feature order |
| `feature_schema` | 62 entries with sequential indices 0–61 |

Reject unknown format versions, missing/reordered features or any coefficient count other than 62.

## Inference contract

Supply:

1. `target_timestamp_unix_seconds`: Unix timestamp of the target hour.
2. Exactly three consecutive preceding Open-Meteo Best Match hourly records, newest first. The newest must be 3,600 seconds before the target; the previous two records must each be 3,600 seconds apart.
3. Weather values for the latest preceding hour from the same location and Open-Meteo Best Match archive series. Missing predictors can be omitted. Do not substitute another provider.
4. The target location's IANA timezone.
5. Geographic context: WGS84 latitude/longitude and optional elevation in metres.

A location-specific model carries a default timezone and location. A shared model must be given the target location's timezone and coordinates explicitly.

Example:

```json
{
  "target_timestamp_unix_seconds": 1800003600,
  "timezone": "Australia/Sydney",
  "location": {"latitude": -33.6153, "longitude": 150.2665, "elevation_m": 1080},
  "previous_open_meteo_hours": [
    {"timestamp_unix_seconds": 1800000000, "temperature_c": 21.8},
    {"timestamp_unix_seconds": 1799996400, "temperature_c": 21.2},
    {"timestamp_unix_seconds": 1799992800, "temperature_c": 20.7}
  ],
  "weather": {
    "humidity_pct": 55,
    "wind_speed_kmh": 12,
    "weather_code": 2,
    "boundary_layer_height_m": 900
  }
}
```

Run `python inference.py model.json request.json`. The script prints a JSON object containing `prediction_c`.

## Weather input keys and units

Values are for the most recent preceding hour. All are Open-Meteo Best Match variables.

| Input key | Unit |
|---|---|
| `dew_point_c`, `wet_bulb_temperature_c`, `apparent_temperature_c`, `soil_temperature_0_to_7cm_c` | °C |
| `wind_speed_kmh`, `wind_gust_kmh`, `wind_speed_100m_kmh` | km/h |
| `pressure_hpa`, `surface_pressure_hpa` | hPa |
| `humidity_pct`, `cloud_cover_pct`, `cloud_low_pct`, `cloud_mid_pct`, `cloud_high_pct` | % |
| `precipitation_mm`, `rain_mm` | mm |
| `snowfall_cm` | cm |
| `snow_depth_m`, `boundary_layer_height_m` | m |
| `soil_moisture_0_to_7cm` | m³/m³ |
| `solar_radiation_wm2`, `direct_radiation_wm2`, `diffuse_radiation_wm2` | W/m² |
| `wind_direction_deg`, `wind_direction_100m_deg` | Degrees clockwise from north |
| `weather_code` | WMO code integer |

Some legacy feature slots remain in the ordered model schema but are not among the 27 requested fields; their corresponding values are therefore null in TempLearn's dataset and are mean-imputed during feature normalization. This does not insert a value into the stored observation.

## Training input

The reference trainer accepts JSON Lines with one hourly Open-Meteo Best Match record per line. Each row must include:

- `location_key`
- `timestamp_unix_seconds`
- `temperature_c`
- `temperature_source="OPEN_METEO_BEST_MATCH"`
- `weather_source="OPEN_METEO_BEST_MATCH"`
- `timezone`, `latitude`, `longitude`
- optional `elevation_m`
- optional `weather` object containing the keys above

The trainer rejects incorrectly labelled sources, nonconsecutive hours and a missing/reordered v3 feature schema.

```bash
python trainer.py model.json open_meteo_training.jsonl updated_model.json
```

## 62-feature ordering

The 62-vector retains the original 53 feature positions and appends nine features before the weather-code category flags:

| Index | Feature |
|---:|---|
| 38 | `snow_depth` |
| 39 | `direct_radiation` |
| 40 | `diffuse_radiation` |
| 41 | `boundary_layer_height` |
| 42 | `wind_speed_100m` |
| 43 | `wind_direction_100m_sin` |
| 44 | `wind_direction_100m_cos` |
| 45 | `soil_temperature_0_7cm` |
| 46 | `soil_moisture_0_7cm` |
| 47–54 | Weather-code category flags |
| 55–61 | Latitude, cyclic longitude, elevation, elevation availability and latitude/season interactions |

Wet-bulb temperature was already represented in the original 53 features. The original 53 feature positions remain unchanged so the extra variables are additive and the Android learner, exported inference and trainer use an identical order.

Numeric missing weather features are normalized to zero after centring/scaling. This is a model preprocessing rule only: missing dataset cells remain null. Training examples are formed only where four consecutive hourly temperatures are present.

## Data provenance and limitations

Open-Meteo Best Match is a reanalysis product, not a station observation. Best Match combines available model products with different spatial resolutions and historical coverage; the available underlying model can vary across time. Some variables are not available for every product or period. TempLearn stores unavailable values as null and never fills them from another provider.

The model predicts the next hour in the same Best Match series it was trained on. Its held-out metrics therefore describe performance within that reanalysis dataset, not independent agreement with a physical weather station or future real-world forecast skill. It is intended for experimental short-horizon temperature learning, not as an official forecast or safety instrument.
