# TempLearn — Open-Meteo hourly weather learning app

TempLearn imports historical hourly weather from the **Open-Meteo Historical Weather API using Best Match**, stores it locally and trains a next-hour air-temperature model on-device. This release is **0.9.3**.

## Data source and import strategy

Open-Meteo Best Match is the only source used for the full historical machine-learning dataset. It combines the available ECMWF IFS, ERA5 and ERA5-Land reanalysis products. Historical reanalysis is a model-based reconstruction, not a direct observation from a local weather station. The underlying product and its resolution can vary across dates, so Best Match is convenient for a continuous hourly learning dataset but is not a single homogeneous climate record.

On first launch, TempLearn presents one setup page with separate actions to download the historical dataset and then pre-train the model. For each saved location, the complete **2000-01-01 through the latest available archive date** history is requested with one Historical Weather API HTTP request and delivered as one CSV response. TempLearn saves that complete CSV to its private on-device files directory before importing any readings into SQLite. Once the file is safely saved and its header is validated, it is imported locally in 256-row database batches. The CSV remains on the device as the original archive. If the connection fails before the file is complete, the partial `.part` file is discarded and the full one-file download must be retried from the beginning; if the complete file was saved but import failed, the next attempt reuses that local CSV without downloading it again. HTTP 429 and transient 5xx responses respect `Retry-After` when supplied and otherwise use exponential backoff. The subsequent pre-training button reads the imported local database and makes no weather API request. Existing 0.9.0 installations are shown this dataset-setup page once after upgrading so they can create the new local CSV archive; choosing “Do this later” dismisses the migration prompt without repeating it on every launch.

Although it is one HTTP request, Open-Meteo's published quota rules weight requests that cover more than 14 days and more than 10 weather variables as multiple API-call units. The one-time 2000-present request for 15 variables is approximately **1,050 quota-equivalent calls** for one location, based on the current date range. The free tier currently lists 10,000 calls per day and 300,000 per month; adding many locations in one day can therefore use a significant share of the daily allowance. The request is deliberately not split into multiple HTTP downloads.

Pre-training is a separate button on the same setup page. It trains from the historical rows already stored on the phone and makes **no weather API requests**. The page displays independent download and training progress. Setup can be completed or postponed; users can return to downloading or training later through the normal refresh workflow.

Subsequent syncs request from the latest stored date through the latest date currently accepted by the Historical Weather API. TempLearn caps `end_date` at yesterday UTC rather than sending today's date, which the API rejects; it also clamps the start date so it cannot exceed the end date. The latest available day is refreshed and any days missed while the app was offline are filled. The full 2000-present archive is not requested again. The Archive API accepts date ranges rather than an hourly cursor, so the latest stored date is intentionally re-read and upserted by timestamp. TempLearn requests hourly variables only; it does not request daily aggregates or a forecast feed.

## The 15 hourly variables

| # | API variable | Unit | Why it is retained |
|---:|---|---|---|
| 1 | `temperature_2m` | °C | Air-temperature learning target |
| 2 | `relative_humidity_2m` | % | Near-surface moisture |
| 3 | `dew_point_2m` | °C | Near-surface water-vapour state |
| 4 | `precipitation` | mm | Total preceding-hour precipitation |
| 5 | `rain` | mm | Liquid precipitation component |
| 6 | `snowfall` | cm | Preceding-hour snowfall |
| 7 | `weather_code` | WMO code | Broad weather-condition category |
| 8 | `cloud_cover` | % | Total cloud amount |
| 9 | `cloud_cover_low` | % | Low cloud layer |
| 10 | `cloud_cover_mid` | % | Middle cloud layer |
| 11 | `cloud_cover_high` | % | High cloud layer |
| 12 | `pressure_msl` | hPa | Mean sea-level pressure |
| 13 | `wind_speed_10m` | km/h | Standard 10 m wind speed |
| 14 | `wind_gusts_10m` | km/h | 10 m wind gusts |
| 15 | `shortwave_radiation` | W/m² | Global shortwave radiation |

**Missing values remain missing.** The app never substitutes another provider, interpolates an absent hour or invents an air-temperature target. Some variables are unavailable in some underlying reanalysis products or periods; those cells remain null. A row without a valid `temperature_2m` value is not inserted as a training target.

## Location handling

Open-Meteo Geocoding resolves a typed place or postcode into a canonical place ID, coordinates, elevation and IANA timezone. Each saved place keeps its own local history and model. The geocoder's elevation is used when available; otherwise TempLearn tries the Open-Meteo Elevation API.

1. On first launch, enter a town, postcode or address and choose the matching geocoded place.
2. Choose a BOM observation station automatically or manually.
3. Download the Open-Meteo historical archive, then start pre-training as a separate step.
4. The selected station mode and WMO station are saved with the location. Later syncs update only the latest stored date range for the Open-Meteo archive.

No GPS permission is requested. The user-entered place query is resolved through Open-Meteo Geocoding.

## BOM observation station reference

During first-time setup, TempLearn separately checks recent observations from the Australian Bureau of Meteorology (BOM). The station can be selected automatically or manually. Automatic selection checks nearby station feeds and prefers the closest station with useful hourly coverage, rather than blindly selecting the nearest station if it only reports once per day. Manual selection presents nearby BOM stations and stores the chosen WMO station identifier. Selecting a station fetches its recent feed (up to the feed's 72-hour window) and shows its latest observed temperature and distance.

**BOM observations are not mixed into the Open-Meteo historical training dataset.** They are labelled as station observations and used as a separate local reference only. The model remains trained and evaluated against Open-Meteo Best Match reanalysis. A station choice such as Mount Boyce therefore does not change the coordinates or grid cell used for the long historical archive. BOM feeds may have different reporting frequencies and not every station reports every hour; missing station hours remain absent. The anonymous BOM feed is intended for personal, non-commercial use; review BOM's current access terms before redistributing or using it commercially.

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

The GitHub Actions workflow runs the Python model/source regression tests and builds the Android APK. It also publishes a `TempLearn-source-project` ZIP artifact that includes the GitHub Actions workflow and Gradle wrapper. **Same-repository pull requests also build, verify with Android `apksigner`, and publish a signed release APK using the configured `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` secrets.** Pull requests from forks cannot access repository secrets on GitHub, so those builds use an unsigned debug APK instead. Push and manual workflow-dispatch builds continue to produce the signed release APK.

## Attribution

Historical and geocoding/elevation data are provided by Open-Meteo. Weather data is licensed under CC BY 4.0. See the [Historical Weather API documentation](https://open-meteo.com/en/docs/historical-weather-api).
