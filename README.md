# TempLearn — Android temperature-learning prototype

TempLearn collects hourly air-temperature observations from the Australian Bureau of Meteorology (BOM), stores them locally, and learns a next-hour temperature relationship on-device. The current release is **0.7.0**.

## What's new in 0.7.0

- Open-Meteo Geocoding now resolves a typed place or postcode into a selectable place record with GeoNames ID, latitude, longitude, elevation, country, administrative area and IANA timezone.
- A location profile is saved once and reused by BOM station discovery and the Open-Meteo Weather API. The chosen elevation is also passed to Open-Meteo's daily request; if geocoding has no elevation, the Open-Meteo 90 m Elevation API is tried.
- Location histories are keyed by the stable geocoding ID. Returning to a previously selected place restores that place's saved BOM history and model. Different same-named places do not share readings.
- The learner now has **53 features**, including normalized latitude, cyclic longitude, elevation, elevation availability and latitude/season interactions. Both local and shared models learn geographic context.
- The model database records training coverage dates, contributing location count and the prior weights used by local adaptation. The shared model combines eligible saved places on this phone only; no other users' observations are uploaded or pooled.
- Model export is now a ZIP model package rather than a weights-only JSON file. It includes `model.json`, the feature schema, inference contract, Python inference and training scripts, evaluation metrics, training metadata, a model card, README and requirements file.
- Visual Crossing is no longer queried; no API key is required for TempLearn 0.7.0.

## Data-source boundaries

**BOM is the exclusive source for historical hourly temperature targets and historical hourly weather predictors used by the model.** Missing BOM temperatures are left missing. Missing BOM predictor fields are not filled from another provider. Each sync accumulates the latest approximately 72 hours of BOM station data into the local database, allowing the on-device history to grow over time.

Open-Meteo Weather contributes the previous completed local calendar day's non-temperature daily aggregates only. Requests use `past_days=1` and `forecast_days=0`; hourly variables, temperature-valued variables and forecast data are not requested. Those daily aggregates are stored separately and are not currently model features.

Open-Meteo Geocoding provides canonical location identity, coordinates, elevation and timezone. This location metadata is used both for provider requests and as model features. Open-Meteo Elevation (global 90 m DEM) is used only when geocoding does not return elevation.

## Location search and updating

1. Open **Settings** and enter a town, postcode or address. Append state/administrative area and country to narrow the search, for example `Katoomba, NSW, Australia` or `2780, NSW, Australia`.
2. Tap **Save location & refresh**.
3. Choose the matching result from the list. TempLearn saves its canonical ID, coordinates, elevation and timezone.
4. The same selected location identity is passed to the BOM station selector and Open-Meteo Weather request. On later refreshes the stored profile is reused.

A previous location's history is retained when switching away. Selecting the same geocoded place again returns to its stored history. If multiple results match on an initial background sync, TempLearn asks you to choose the right result in Settings rather than silently guessing.

## Learning algorithm

TempLearn uses **multivariable linear ridge regression trained locally with batch gradient descent**. The 53-element feature vector contains local hour and annual cycles; the preceding three consecutive BOM temperatures; the latest BOM hour's available atmospheric, wind, cloud, precipitation and radiation variables; WMO condition flags; and geographic features (latitude, cyclic longitude and elevation). The shared learner combines eligible saved locations on this phone, constructing geographic features from each place's own profile. It does not pool observations across users or a remote server. Each location also has a locally adapted model.

The dashboard's validation MAE and bias are calculated on chronological held-out examples. They are diagnostics, not guarantees of future accuracy. Once a shared model exists, a newly selected geocoded location can use it immediately when three consecutive BOM hours are available; after sufficient local history accumulates, its locally adapted model takes precedence. Each saved place's observations remain separate.

## Portable model export

Settings offers two ZIP exports:

- **This location's model package:** fitted model for the active location, including its default timezone and geographic context.
- **Shared model package:** model trained across contributing locations. The consuming app must provide the target location's coordinates and timezone during inference.

Each package contains:

| File | Purpose |
|---|---|
| `model.json` | Model architecture, fitted coefficients, 53-feature schema, preprocessing, geographic metadata and learned state |
| `feature_schema.json` | Ordered machine-readable feature definitions |
| `inference.py` | Standalone Python inference reference and CLI |
| `trainer.py` | Standalone reference ridge-regression trainer for new BOM JSONL data |
| `inference_contract.json` | Required inputs and provenance rules |
| `training_metadata.json` / `evaluation.json` | Coverage and held-out metrics |
| `MODEL_CARD.md` / `README.md` | Intended use, caveats and instructions |
| `requirements.txt` | Runtime requirements (Python standard library only) |

Example inference command after extracting a package: `python inference.py model.json request.json`.

The package contains model parameters and code, not the user's raw hourly readings or API credentials. Geographic training-location metadata is included because it is part of the learned feature context.

## Storage and location identity

- No GPS permission is requested. Location search uses the text entered by the user and the Open-Meteo Geocoding API.
- Hourly BOM readings, daily Open-Meteo context, selected location profiles and fitted models are stored in the app's private SQLite database.
- Location profiles use the stable geocoding result ID. A database migration re-keys compatible legacy location records without deleting the underlying readings.
- Switching locations does not delete another location's records.
- The app does not send stored hourly BOM records to a server; current requests are read-only API lookups.

## Build through GitHub Actions

1. Extract the ZIP and push the **contents of the `TempLearn` folder** to the root of a GitHub repository. Include hidden files such as `.github`.
2. Open the repository's **Actions** tab and choose **Build TempLearn Android APK**. Select **Run workflow**. It also runs on pushes and pull requests targeting `main` or `master`.
3. For normal branch builds, the workflow restores and verifies the configured release signing keystore secrets, then builds `app-release.apk`. Pull requests build a debug APK.
4. Download the APK from the completed workflow run's **Artifacts** section.

No weather API key is required for TempLearn 0.7.0. The Gradle build uses GitHub Actions' configured Gradle 8.9 and Java 17. A complete Android build should be confirmed by the GitHub Actions run; this source package is not an APK.

## Important limitations

- BOM's public observation network is Australian and provides a rolling approximately 72-hour window. Although Open-Meteo can resolve locations worldwide, this build deliberately has no alternative historical temperature provider; places outside usable BOM coverage cannot accumulate training targets or produce an in-app next-hour estimate. Long-term training history grows only while the app is installed and successfully syncing.
- Nearby-station selection is based on available BOM station metadata and its suitability checks; the selected station may not be exactly at the geocoded town centre.
- The regression model is experimental and cannot capture every nonlinear atmospheric process. The shared model is shared only across saved, geocoded places on this phone; it does not pool other users' data. Its geographic generalisation depends on how representative those local training places are.
- Open-Meteo daily aggregates are not currently used as model features. They are retained separately for future analysis, with no temperature variables requested.
- Open-Meteo location and weather data require attribution under the provider's published terms. See https://open-meteo.com and https://creativecommons.org/licenses/by/4.0/.


## Version 0.7.1 build fix

Enabled Android `BuildConfig` generation explicitly for AGP 8.x. This fixes unresolved `BuildConfig` references in provider clients such as `VisualCrossingClient.kt`. Version code: 13.
