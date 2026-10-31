# TempLearn — Open-Meteo hourly weather learning app

TempLearn imports historical hourly weather from the **Open-Meteo Historical Weather API using Best Match**, stores it locally and trains a next-hour air-temperature model on-device. This release is **0.8.0**.

## Data source and import strategy

Open-Meteo Best Match is the only weather-data source used by TempLearn. It combines the available ECMWF IFS, ERA5 and ERA5-Land reanalysis products. Historical reanalysis is a model-based reconstruction, not a direct observation from a local weather station. The underlying product and its resolution can vary across dates, so Best Match is convenient for a continuous hourly learning dataset but is not a single homogeneous climate record.

The first successful sync for each saved location requests the full hourly archive from **2000-01-01 through the current local date** in one API request. The response is CSV and is streamed into SQLite in batches of 256 rows, rather than being parsed as a huge JSON object in memory. The per-location completion flag is set only after the response is fully read. If the request is interrupted, saved batches remain and the next sync resumes from the latest stored date.

Subsequent syncs request from the latest stored local date through the current local date, so the most recent day is refreshed and any days missed while the app was offline are filled. The full 2000-present archive is not requested again. The Archive API accepts date ranges rather than an hourly cursor, so the latest stored date is intentionally re-read and upserted by timestamp. TempLearn requests hourly variables only; it does not request daily aggregates or a forecast feed.

## The 27 hourly variables

| # | API variable | Unit | Why it is retained |
|---:|---|---|---|
| 1 | `temperature_2m` | °C | Air-temperature learning target |
| 2 | `apparent_temperature` | °C | Combined apparent/feels-like temperature |
| 3 | `relative_humidity_2m` | % | Near-surface moisture |
| 4 | `dew_point_2m` | °C | Near-surface water-vapour state |
| 5 | `precipitation` | mm | Total preceding-hour precipitation |
| 6 | `rain` | mm | Liquid precipitation component |
| 7 | `snowfall` | cm | Preceding-hour snowfall |
| 8 | `snow_depth` | m | Snow cover on the ground |
| 9 | `weather_code` | WMO code | Broad weather-condition category |
| 10 | `cloud_cover` | % | Total cloud amount |
| 11 | `cloud_cover_low` | % | Low cloud layer |
| 12 | `cloud_cover_mid` | % | Middle cloud layer |
| 13 | `cloud_cover_high` | % | High cloud layer |
| 14 | `surface_pressure` | hPa | Pressure at the local surface |
| 15 | `pressure_msl` | hPa | Mean sea-level pressure |
| 16 | `wind_speed_10m` | km/h | Standard 10 m wind speed |
| 17 | `wind_direction_10m` | ° | Standard 10 m wind direction |
| 18 | `wind_gusts_10m` | km/h | 10 m wind gusts |
| 19 | `shortwave_radiation` | W/m² | Global shortwave radiation |
| 20 | `direct_radiation` | W/m² | Direct solar radiation |
| 21 | `diffuse_radiation` | W/m² | Diffuse solar radiation |
| 22 | `wet_bulb_temperature_2m` | °C | Humidity-sensitive thermal state |
| 23 | `boundary_layer_height` | m | Atmospheric mixing-layer depth |
| 24 | `wind_speed_100m` | km/h | Wind above the surface layer |
| 25 | `wind_direction_100m` | ° | Upper-level wind direction |
| 26 | `soil_temperature_0_to_7cm` | °C | Shallow ground thermal state |
| 27 | `soil_moisture_0_to_7cm` | m³/m³ | Shallow ground water content |

Wet-bulb temperature was already present in the earlier feature set; it remains part of the 27-variable request. Boundary-layer height, 100 m wind, and shallow-soil variables add information about atmospheric mixing, vertical wind structure and ground conditions. Snow depth and direct/diffuse radiation are also now retained as training features.

**Missing values remain missing.** The app never substitutes another provider, interpolates an absent hour or invents an air-temperature target. Some variables are unavailable in some underlying reanalysis products or periods; those cells remain null. A row without a valid `temperature_2m` value is not inserted as a training target.

## Location handling

Open-Meteo Geocoding resolves a typed place or postcode into a canonical place ID, coordinates, elevation and IANA timezone. Each saved place keeps its own local history and model. The geocoder's elevation is used when available; otherwise TempLearn tries the Open-Meteo Elevation API.

1. Open **Settings** and enter a town, postcode or address.
2. Tap **Save location & refresh**.
3. Choose the matching place.
4. The first sync imports the archive; later syncs update only the unimported/recent date range.

No GPS permission is requested. The user-entered place query is resolved through Open-Meteo Geocoding.

## Learning model

TempLearn uses multivariable linear ridge regression trained on the phone. The feature vector contains **62 ordered features**: local hour and annual cycles; the previous three consecutive hourly temperatures; near-surface thermodynamic, pressure, wind, cloud, precipitation and radiation fields; nine additional engineered signals for snow depth, direct/diffuse radiation, boundary-layer height, 100 m wind speed/direction and shallow-soil conditions; WMO condition flags; and geographic context.

Wind directions are represented with sine/cosine pairs so the 0°/360° boundary is handled correctly. Missing numeric predictors are mean-imputed only inside feature normalization; the database still stores null, and missing temperature observations or hours are never interpolated.

The full hourly archive remains in SQLite. For bounded training memory, model fitting loads spaced windows of four consecutive hours, capped at approximately 4,000 candidate examples per location. Saved locations are trained separately and a shared model can use their geographic context; observations are not uploaded or pooled with other users.

The dashboard's chronological held-out MAE and bias are diagnostics, not guarantees of future forecast accuracy. The model predicts temperature in the same Open-Meteo reanalysis series it was trained on, not independent station measurements.

## Database migration

Database version 12 clears old BOM and mixed-provider weather rows and fitted weights once, while retaining saved location profiles. Existing BOM temperatures are not relabelled as Open-Meteo reanalysis. The next sync builds a clean Open-Meteo Best Match history.

## Portable model export

The app exports a standalone model package using **format version 3** and the 62-feature schema. It contains the fitted coefficients, feature definitions, inference contract, standard-library Python inference and training scripts, training metadata, validation metrics and a model card. Raw hourly readings and API credentials are not included.

## Build

Run locally:

```bash
./gradlew --no-daemon assembleDebug
```

The GitHub Actions workflow runs the Python model/source regression tests and builds the Android APK. It also publishes a `TempLearn-source-project` ZIP artifact that includes the GitHub Actions workflow and Gradle wrapper. Pull-request builds use the debug APK; signed release builds require the configured Android signing secrets.

## Attribution

Historical and geocoding/elevation data are provided by Open-Meteo. Weather data is licensed under CC BY 4.0. See the [Historical Weather API documentation](https://open-meteo.com/en/docs/historical-weather-api).
