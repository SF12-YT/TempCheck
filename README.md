# TempLearn — Open-Meteo hourly weather learning app

TempLearn imports historical hourly weather from the **Open-Meteo Historical Weather API using Best Match**, retains the original dataset on-device and trains a next-hour air-temperature model locally. This release is **0.10.7**.

## Background service diagnostic

Version 0.10.7 adds an optional Settings test that starts a user-initiated Android foreground service. It refreshes a single ongoing notification every five seconds and is intended to test whether the service continues after TempLearn is dismissed from Recents. Stop it from Settings or from the notification. Android can still stop the service after an explicit force-stop or system-level termination; this is a diagnostic, not a guarantee of uninterrupted execution.

## First launch and historical archive

The first-run setup page has been removed. TempLearn opens directly to the dashboard and automatically downloads the historical dataset, imports it into the local database and pre-trains the model.

For the default location (2780, NSW, Australia), first launch downloads the published Open-Meteo JSON snapshot from the openmeteo release in the SF12-YT/dummy-assets repository. The snapshot covers **1 January 2000 through 31 October 2026**, including the complete latest available day in that file. The transfer is one complete file, not date segments. TempLearn verifies its SHA-256 and expected byte size before promoting the download from a temporary .part file. The original JSON is then retained unchanged in the app's private on-device files directory, including hourly variables that the current learner does not yet use. If importing fails after the download completes, the saved JSON is reused rather than downloaded again.

The JSON importer streams the response structure and imports the 15 hourly fields currently used by the learner in 256-row database batches. Null predictor values remain null, and rows without a valid temperature_2m target are skipped. The coordinates, elevation and timezone recorded in the JSON are used for the model's geographic context.

Other locations continue to use a single complete CSV response from the Open-Meteo Historical Weather API. Subsequent syncs for all locations request only the latest stored date through the latest date accepted by the API, then upsert records by timestamp. This fills days missed while the app was offline without downloading the full archive again. The request end date is capped at yesterday UTC because the Archive API does not accept today's date. Routine refreshes also re-train the local model after importing new readings.

The default-location JSON snapshot is an immutable historical dataset, not a live feed. Daily refreshes use Open-Meteo Archive API to add the latest available date after the snapshot. Historical reanalysis is a model-based reconstruction, not a direct observation from a local weather station. Best Match products and their resolution may vary across dates, so this is a continuous learning dataset rather than a homogeneous station record.

## The 15 hourly training variables

| # | API variable | Unit | Why it is retained |
|---:|---|---|---|
| 1 | temperature_2m | °C | Air-temperature learning target |
| 2 | relative_humidity_2m | % | Near-surface moisture |
| 3 | dew_point_2m | °C | Near-surface water-vapour state |
| 4 | precipitation | mm | Total preceding-hour precipitation |
| 5 | rain | mm | Liquid precipitation component |
| 6 | snowfall | cm | Preceding-hour snowfall |
| 7 | weather_code | WMO code | Broad weather-condition category |
| 8 | cloud_cover | % | Total cloud amount |
| 9 | cloud_cover_low | % | Low cloud layer |
| 10 | cloud_cover_mid | % | Middle cloud layer |
| 11 | cloud_cover_high | % | High cloud layer |
| 12 | pressure_msl | hPa | Mean sea-level pressure |
| 13 | wind_speed_10m | km/h | Standard 10 m wind speed |
| 14 | wind_gusts_10m | km/h | 10 m wind gusts |
| 15 | shortwave_radiation | W/m² | Global shortwave radiation |

The retained JSON contains additional hourly variables, but the raw file is deliberately preserved intact so those columns remain available for future learning features. The current database importer only maps the learner's selected 15 fields.

**Missing values remain missing.** TempLearn does not substitute another provider, interpolate an absent hour or invent an air-temperature target. Some variables are unavailable in some underlying reanalysis products or periods; those cells remain null.

## Location handling

Open-Meteo Geocoding resolves a typed place or postcode into a canonical place ID, coordinates, elevation and IANA timezone. Each saved place keeps its own local history and model. The geocoder's elevation is used when available; otherwise TempLearn tries the Open-Meteo Elevation API.

The default location is loaded automatically on first launch. To use another location, open Settings, search for a postcode, town or address, and select the matching place. Its history is downloaded from Open-Meteo and stored separately. No GPS permission is requested.

## Learning model

TempLearn uses multivariable linear ridge regression trained on the phone. The feature vector contains **62 ordered features**: local hour and annual cycles; the previous three consecutive hourly temperatures; near-surface thermodynamic, pressure, wind, cloud, precipitation and radiation fields; nine additional engineered signals for snow depth, direct/diffuse radiation, boundary-layer height, 100 m wind speed/direction and shallow-soil conditions; WMO condition flags; and geographic context.

Wind directions are represented with sine/cosine pairs so the 0°/360° boundary is handled correctly. Missing numeric predictors are mean-imputed only inside feature normalization; the database still stores null, and missing temperature observations or hours are never interpolated.

The full hourly archive remains in SQLite. For bounded training memory, model fitting loads spaced windows of four consecutive hours, capped at approximately 4,000 candidate examples per location. Saved locations are trained separately and a shared model can use their geographic context; observations are not uploaded or pooled with other users.

The dashboard's chronological held-out MAE and bias are diagnostics, not guarantees of future forecast accuracy. The model predicts temperature in the same Open-Meteo Best Match reanalysis series it was trained on, not independent station measurements.

## Portable model export

The app exports a standalone model package using **format version 3** and the 62-feature schema. It contains the fitted coefficients, feature definitions, inference contract, standard-library Python inference and training scripts, training metadata, validation metrics and a model card. Raw hourly readings and API credentials are not included.

## Build

Run locally:

    ./gradlew --no-daemon assembleDebug

The GitHub Actions workflow runs the Python model/source regression tests and builds the Android APK. It also publishes a TempLearn-source-project ZIP artifact that includes the GitHub Actions workflow and Gradle wrapper. Push and manual workflow-dispatch builds use the configured ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD secrets to build a signed release APK, verify it with Android apksigner, and publish the APK artifact.

## Attribution

Historical and geocoding/elevation data are provided by Open-Meteo. Weather data is licensed under CC BY 4.0. See the [Historical Weather API documentation](https://open-meteo.com/en/docs/historical-weather-api).
