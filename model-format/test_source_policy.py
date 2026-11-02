"""Static regression checks for the Open-Meteo Best Match hourly-only pipeline."""
from pathlib import Path
import re
import unittest

from reference_predictor import EXPECTED_FEATURES, FORMAT_VERSION

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/example/tempalearn"
ASSETS = ROOT / "app/src/main/assets/model-package"

HOURLY_FIELDS = (
    "temperature_2m", "relative_humidity_2m", "dew_point_2m", "precipitation",
    "rain", "snowfall", "weather_code", "cloud_cover", "cloud_cover_low",
    "cloud_cover_mid", "cloud_cover_high", "pressure_msl", "wind_speed_10m",
    "wind_gusts_10m", "shortwave_radiation",
)


class SourcePolicyTests(unittest.TestCase):
    def test_archive_and_forecast_use_the_exact_15_hourly_fields(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        archive = client.split("fun downloadHourlyArchiveToFile", 1)[1].split(
            "fun downloadRecentHourlyForecastToFile", 1
        )[0]
        forecast = client.split("fun downloadRecentHourlyForecastToFile", 1)[1].split(
            "private fun downloadCsvToFile", 1
        )[0]
        self.assertIn("models=best_match", archive)
        self.assertIn("format=csv", archive)
        self.assertNotIn("past_days=", archive)
        self.assertIn("https://api.open-meteo.com/v1/forecast", forecast)
        self.assertIn("&past_hours=$safePastHours&forecast_hours=0", forecast)
        self.assertIn("pastHours: Int = 24", forecast)
        self.assertIn("pastHours.coerceIn(1, 24 * 92)", forecast)
        self.assertNotIn("start_date=", forecast)
        self.assertNotIn("end_date=", forecast)
        self.assertIn("&models=best_match", forecast)
        self.assertNotIn("past_days=", forecast)
        self.assertNotIn("forecast_days=", forecast)
        self.assertIn("&timeformat=unixtime", forecast)
        self.assertIn("&format=csv", forecast)
        self.assertIn("&hourly=", client)
        self.assertNotIn("&daily=", client)
        start = client.index("private val hourlyFields")
        end = client.index("    )", start)
        selected = re.findall(r'"([a-z0-9_]+)"', client[start:end])
        self.assertEqual(tuple(selected), HOURLY_FIELDS)
        self.assertEqual(len(selected), 15)
        self.assertIn("DEFAULT_LATITUDE = -33.6153", client)
        self.assertIn("DEFAULT_LONGITUDE = 150.2665", client)

    def test_default_place_is_katoomba_with_manual_mount_boyce_data_point(self):
        preferences = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        main = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn('DEFAULT_LOCATION = "Katoomba, NSW, Australia"', preferences)
        self.assertIn('DEFAULT_WEATHER_POINT_NAME = "Mount Boyce"', preferences)
        self.assertIn('name = "Katoomba"', preferences)
        self.assertIn("DEFAULT_LATITUDE = -33.6153", client)
        self.assertIn("DEFAULT_LONGITUDE = 150.2665", client)
        self.assertIn("Choose Open-Meteo data location", main)
        self.assertIn('setPositiveButton("Automatic")', main)
        self.assertIn('setNeutralButton("Manual…")', main)
        self.assertIn("promptManualWeatherLocation", main)
        self.assertIn("weatherDataLocationKey(place.id, weatherPlace.latitude, weatherPlace.longitude)", main)
        self.assertIn("weatherPointModeKey", preferences)
        self.assertIn("weatherPointNameKey", preferences)

    def test_default_json_archive_is_used_only_for_mount_boyce_point(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        self.assertIn("isDefaultLocation &&", sync)
        self.assertIn("abs(profile.latitude - OpenMeteoClient.DEFAULT_LATITUDE) <= 0.001", sync)
        self.assertIn("abs(profile.longitude - OpenMeteoClient.DEFAULT_LONGITUDE) <= 0.001", sync)
        self.assertIn("weatherPointModeKey(locationKey)", sync)

    def test_forecast_csv_header_normalizes_open_meteo_unit_suffixes(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        self.assertIn('private fun normalizeCsvHeaderCell', client)
        self.assertIn('cleaned.substringBefore(" (").trim()', client)
        self.assertIn('splitCsv(line).mapIndexed(::normalizeCsvHeaderCell)', client)
        self.assertIn('header = normalizedCells', client)
        self.assertIn('Header preview:', client)
        self.assertIn('versionName = "0.10.12"', (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"))

    def test_dashboard_displays_cloud_forecasts_instead_of_local_predictions(self):
        dashboard = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        cloud_client = (KOTLIN / "CloudSyncClient.kt").read_text(encoding="utf-8")
        worker = (ROOT / "cloud/src/index.ts").read_text(encoding="utf-8")
        self.assertIn('cloud_dashboard_$locationKey', dashboard)
        self.assertIn('cloudModelAvailable', dashboard)
        self.assertIn('cloudSharedModelAvailable', dashboard)
        self.assertIn('latestForecast', dashboard)
        self.assertIn('predictedTempC', dashboard)
        self.assertNotIn('predictAndStoreNextHour(', dashboard)
        self.assertNotIn('WeatherLearner.train', dashboard)
        self.assertNotIn('WeatherLearner.predict', dashboard)
        self.assertIn('"/v1/dashboard?locationId="', cloud_client)
        self.assertIn('async function generateForecast', worker)
        self.assertIn('past_hours: "96"', worker)

    def test_five_second_test_posts_distinct_high_importance_alerts(self):
        service = (KOTLIN / "BackgroundNotificationTestService.kt").read_text(encoding="utf-8")
        main = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("TICK_INTERVAL_MS = 5_000L", service)
        self.assertIn("NotificationManager.IMPORTANCE_HIGH", service)
        self.assertIn("postTestAlertNotification()", service)
        self.assertIn("manager.notify(notificationId, notification)", service)
        self.assertIn('ALERT_CHANNEL_ID = "templearn_background_test_alerts_v2"', service)
        self.assertIn("REQUEST_BACKGROUND_TEST_NOTIFICATIONS", main)
        self.assertIn("onRequestPermissionsResult", main)
        self.assertIn("areNotificationsEnabled()", main)

    def test_archive_streams_csv_and_json_and_preserves_missing_fields(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        self.assertIn("BufferedReader", client)
        self.assertIn("JsonReader", client)
        self.assertIn("batch.size >= 256", client)
        self.assertIn("numberAt(indices", client)
        self.assertIn("importHourlyArchiveFile", client)
        self.assertIn("importHourlyArchiveJsonFile", client)
        self.assertIn("downloadHourlyArchiveToFile", client)
        self.assertIn("downloadHistoricalJsonToFile", client)
        self.assertIn('?: continue', client)

    def test_default_location_imports_the_json_snapshot_once(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        self.assertIn("historyCompleteKey", sync)
        self.assertIn("history_file_complete_v3", sync)
        self.assertIn("usePublishedJson", sync)
        self.assertIn("OpenMeteoClient.downloadHistoricalJsonToFile", sync)
        self.assertIn("OpenMeteoClient.importHourlyArchiveJsonFile", sync)
        self.assertIn("open_meteo_best_match_full_history.json", sync)
        self.assertIn("OpenMeteoClient.downloadHourlyArchiveToFile", sync)
        self.assertIn("OpenMeteoClient.importHourlyArchiveFile", sync)
        self.assertIn("fun pretrainHistoricalDataset", sync)
        self.assertNotIn("dateChunks", sync)
        self.assertNotIn("fetchHourlyArchive(", sync)
        self.assertIn("HISTORICAL_JSON_SHA256", client)
        self.assertIn("MessageDigest", client)
        self.assertIn("unrecognised hourly", client)
        self.assertIn("fun downloadHistoricalDataset", sync)
        self.assertNotIn("BomClient", sync)
        self.assertNotIn("KEY_STATION_", sync)
        self.assertNotIn("KEY_ONE_FILE_ARCHIVE_SETUP_COMPLETE", sync)

    def test_cloud_is_authoritative_and_uses_the_96_hour_past_only_feed(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        cloud_client = (KOTLIN / "CloudSyncClient.kt").read_text(encoding="utf-8")
        worker = (ROOT / "cloud/src/index.ts").read_text(encoding="utf-8")
        orchestration = sync.split("fun sync(", 1)[1].split("\n    /**", 1)[0]
        self.assertIn("downloadHistoricalDataset", orchestration)
        self.assertIn("CloudSyncClient.bootstrapAndSync", orchestration)
        self.assertNotIn("pretrainHistoricalDataset", orchestration)
        self.assertNotIn("refreshRecentForecastDataset", orchestration)
        self.assertIn("PAGE_SIZE = 1000", cloud_client)
        self.assertIn("/v1/bootstrap/batch", cloud_client)
        self.assertIn('past_hours: "96"', worker)
        self.assertIn('forecast_hours: "0"', worker)
        self.assertIn('models: "best_match"', worker)
        self.assertIn("INSERT OR IGNORE INTO cloud_observations", worker)
        self.assertIn("futureProviderTemperatureInputs: false", worker)

    def test_recent_forecast_requests_expandable_past_hours_for_pending_targets(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        forecast = client.split("fun downloadRecentHourlyForecastToFile", 1)[1].split(
            "private fun downloadCsvToFile", 1
        )[0]
        self.assertIn("&past_hours=$safePastHours&forecast_hours=0", forecast)
        self.assertIn("pastHours: Int = 24", forecast)
        self.assertIn("pastHours.coerceIn(1, 24 * 92)", forecast)
        self.assertNotIn("start_date=", forecast)
        self.assertNotIn("end_date=", forecast)
        self.assertIn("&models=best_match", forecast)
        self.assertIn("&timeformat=unixtime", forecast)

        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        refresh = sync.split("private fun refreshRecentForecastDataset", 1)[1].split(
            "fun pretrainHistoricalDataset", 1
        )[0]
        self.assertIn("profile.timezoneId,\n                recentForecastFile,\n                pastHours = pendingLookbackHours", refresh)
        self.assertNotIn("val yesterday =", refresh)
        self.assertIn("predictWalkForwardTarget", refresh)
        self.assertIn("db.insertReadingsIfAbsent(locationKey, listOf(reading))", refresh)
        self.assertLess(refresh.index("predictWalkForwardTarget"), refresh.index("db.insertReadingsIfAbsent(locationKey, listOf(reading))"))

    def test_completed_history_can_be_kept_in_sqlite_without_the_source_json(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        self.assertIn("val historyComplete = prefs.getBoolean(completeKey, false)", sync)
        self.assertIn("val initialNeeded = !historyComplete", sync)
        self.assertNotIn("|| !archiveFile.isFile", sync)
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        self.assertIn('writable.insertWithOnConflict("readings"', database)
        self.assertIn('writableDatabase.insertWithOnConflict("model_state"', database)
        self.assertIn("fun getAllReadingsByLocation()", database)

    def test_initial_history_is_transferred_and_trained_in_the_cloud(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        client = (KOTLIN / "CloudSyncClient.kt").read_text(encoding="utf-8")
        worker = (ROOT / "cloud/src/index.ts").read_text(encoding="utf-8")
        orchestration = sync.split("fun sync(", 1)[1].split("\n    /**", 1)[0]
        self.assertIn("downloadHistoricalDataset", orchestration)
        self.assertIn("CloudSyncClient.bootstrapAndSync", orchestration)
        self.assertIn("getReadingsPage", client)
        self.assertIn("/v1/bootstrap/start", client)
        self.assertIn("/v1/bootstrap/complete", client)
        self.assertIn("trainLocalFromExamples", worker)
        self.assertIn("trainSharedFromSeriesExamples", worker)
        self.assertIn("runWalkForwardReplay", worker)

    def test_reduced_archive_invalidates_models_after_reimport(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        self.assertIn("db.clearModels()", sync)
        self.assertIn('writableDatabase.delete("model_state", null, null)', database)
        self.assertIn("open_meteo_best_match_2000_present_v3.csv", sync)
        self.assertIn("open_meteo_best_match_2000_present.csv", sync)
        self.assertIn("open_meteo_best_match_full_history.json", sync)

    def test_overlapping_hourly_timestamps_are_ignored(self):
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        self.assertIn("SQLiteDatabase.CONFLICT_IGNORE", database)
        self.assertIn("fun insertReadingsIfAbsent", database)
        self.assertGreaterEqual(sync.count("db.insertReadingsIfAbsent(locationKey, batch)"), 2)
        self.assertIn("WALK_FORWARD_REPLAY", sync)
        self.assertIn("PRIMARY KEY(location_key, timestamp_sec)", database)

    def test_recent_observation_replay_excludes_targets_from_training_and_backfills_existing_rows(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        refresh = sync.split("private fun refreshRecentForecastDataset", 1)[1].split(
            "fun pretrainHistoricalDataset", 1
        )[0]
        self.assertIn("val replayCutoffSec = Instant.now().epochSecond - 24L * 60L * 60L", refresh)
        self.assertIn("val allReadingsForReplay = db.getAllReadingsByLocation()[locationKey].orEmpty()", refresh)
        self.assertIn("it.timestampSec < replayCutoffSec", refresh)
        self.assertIn("val replayModel = WeatherLearner.trainLocal", refresh)
        self.assertIn("val existingRecentReadings = existingLatest.filter", refresh)
        self.assertIn("val feedbackBeforeTarget = db.getVerifiedPredictionErrors(locationKey)", refresh)
        self.assertIn("it.targetTimestampSec < reading.timestampSec", refresh)
        self.assertIn("verificationMethod = PredictionVerificationMethod.WALK_FORWARD_REPLAY", refresh)
        self.assertLess(
            refresh.index("predictWalkForwardTarget"),
            refresh.index("db.insertReadingsIfAbsent(locationKey, listOf(reading))")
        )
        self.assertNotIn("db.saveModel(locationKey, replayModel", refresh)

    def test_hourly_background_refresh_uses_phone_timezone_and_top_of_hour_alarm(self):
        scheduler = (KOTLIN / "HourlySync.kt").read_text(encoding="utf-8")
        main = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        notifications = (KOTLIN / "NotificationHelper.kt").read_text(encoding="utf-8")
        self.assertIn("ZoneId.systemDefault()", scheduler)
        self.assertIn("withMinute(0).withSecond(0).withNano(0).plusHours(1)", scheduler)
        self.assertIn("setExactAndAllowWhileIdle", scheduler)
        self.assertIn("canScheduleExactAlarms()", scheduler)
        self.assertIn("setAndAllowWhileIdle", scheduler)
        self.assertIn("ACTION_TIMEZONE_CHANGED", scheduler)
        self.assertIn("ACTION_BOOT_COMPLETED", scheduler)
        self.assertIn("ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED", scheduler)
        self.assertIn("startForegroundService", scheduler)
        self.assertIn("SyncRepository.sync(applicationContext, location)", scheduler)
        self.assertIn('source = "hourly"', scheduler)
        self.assertIn('source == "hourly" -> "TempLearn hourly update successful"', notifications)
        self.assertIn("fun showSyncFailure", notifications)
        self.assertIn('android:name=".HourlySyncService"', manifest)
        self.assertIn("android.permission.SCHEDULE_EXACT_ALARM", manifest)
        self.assertIn("android.permission.FOREGROUND_SERVICE_DATA_SYNC", manifest)
        self.assertIn("HourlySyncScheduler.ensureScheduled(applicationContext)", main)
        self.assertIn("maybePromptExactHourlyAlarmAccess", main)
        self.assertIn("HourlySyncScheduler.rescheduleForLocation(applicationContext)", main)

    def test_feedback_ui_uses_cloud_verification_counts(self):
        main = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        worker = (ROOT / "cloud/src/index.ts").read_text(encoding="utf-8")
        self.assertIn("walk-forward checks", main)
        self.assertIn("live verified", main)
        self.assertIn("walkForwardVerified", main)
        self.assertIn("liveVerified", main)
        self.assertIn("verification_method TEXT NOT NULL", worker)
        self.assertIn("WALK_FORWARD_REPLAY", worker)
        self.assertIn('actual.temperatureC,"LIVE",verifiedAtSec', worker)

    def test_live_forecast_targets_next_local_hour_when_observations_lag(self):
        feedback = (KOTLIN / "PredictionFeedback.kt").read_text(encoding="utf-8")
        learner = (KOTLIN / "TemperatureLearner.kt").read_text(encoding="utf-8")
        self.assertIn("nextLocalHourTimestamp(nowSec, timezoneId)", feedback)
        self.assertIn("WeatherLearner.predictAtTimestamp", feedback)
        self.assertIn("MAX_LIVE_FORECAST_LEAD_HOURS = 3L", learner)
        self.assertNotIn("if (base.first > nowSec)", feedback)

    def test_database_discards_legacy_provider_data_on_upgrade(self):
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        self.assertIn("DATABASE_VERSION = 14", database)
        self.assertIn("verification_method TEXT NOT NULL DEFAULT 'LIVE'", database)
        self.assertIn("oldVersion >= 13 && oldVersion < 14", database)
        self.assertIn("OpenMeteoClient.SOURCE", database)
        self.assertIn('db.execSQL("DELETE FROM readings")', database)
        self.assertIn('db.execSQL("DELETE FROM model_state")', database)
        self.assertIn("soil_moisture_0_7cm", database)

    def test_android_and_python_models_share_62_feature_order(self):
        learner = (KOTLIN / "TemperatureLearner.kt").read_text(encoding="utf-8")
        exporter = (KOTLIN / "WeatherModelExport.kt").read_text(encoding="utf-8")
        self.assertIn("WEIGHT_COUNT = 62", learner)
        self.assertEqual(re.findall(r'Feature\("([^"]+)"', exporter), EXPECTED_FEATURES)
        self.assertEqual(len(EXPECTED_FEATURES), 62)
        self.assertEqual(FORMAT_VERSION, 3)

    def test_portable_python_assets_match_and_enforce_open_meteo(self):
        inference = (ASSETS / "inference.py").read_text(encoding="utf-8")
        trainer = (ASSETS / "trainer.py").read_text(encoding="utf-8")
        self.assertEqual(inference, (ROOT / "model-format/reference_predictor.py").read_text(encoding="utf-8"))
        self.assertEqual(trainer, (ROOT / "model-format/trainer.py").read_text(encoding="utf-8"))
        self.assertIn('!= "OPEN_METEO_BEST_MATCH"', trainer)
        self.assertIn("FORMAT_VERSION = 3", inference)
        self.assertNotIn("BOM", inference)
        self.assertNotIn("BOM", trainer)

    def test_geocoding_is_still_the_location_resolver(self):
        client = (KOTLIN / "LocationGeocodingClient.kt").read_text(encoding="utf-8")
        self.assertIn("geocoding-api.open-meteo.com/v1/search", client)
        for field in ("latitude", "longitude", "elevation", "timezone", "country_code", "admin1"):
            self.assertIn(f'"{field}"', client)
        self.assertIn("Several places match", client)

    def test_bom_station_and_first_launch_activities_are_removed(self):
        self.assertFalse((KOTLIN / "BomClient.kt").exists())
        self.assertFalse((KOTLIN / "FirstLaunchSetupActivity.kt").exists())
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        main = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        self.assertNotIn("FirstLaunchSetupActivity", manifest)
        self.assertNotIn("FirstLaunchSetupActivity", main)
        self.assertNotIn("BomClient", sync)
        self.assertNotIn("BOM station", main)

    def test_initial_download_and_pretraining_start_automatically(self):
        main = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("buildInterface()", main)
        self.assertIn("if (shouldAutoSync()) syncNow(manual = false)", main)
        self.assertIn("historyCompleteKey(key)", main)
        self.assertIn("Downloading the complete Open-Meteo historical archive", main)
        self.assertIn("fun sync(", (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8"))

    def test_same_repository_pull_requests_build_a_signed_release_apk(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        trusted_pr = "github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository"
        fork_only = "github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository"
        self.assertEqual(workflow.count("if: " + trusted_pr), 4)
        self.assertEqual(workflow.count("if: " + fork_only), 2)
        self.assertIn("ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}", workflow)
        self.assertIn("run: gradle --no-daemon assembleRelease", workflow)
        self.assertIn("verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk", workflow)
        self.assertIn("run: gradle --no-daemon assembleDebug", workflow)

    def test_cloud_owns_verified_error_calibration(self):
        feedback = (KOTLIN / "PredictionFeedback.kt").read_text(encoding="utf-8")
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        client = (KOTLIN / "CloudSyncClient.kt").read_text(encoding="utf-8")
        main = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        worker = (ROOT / "cloud/src/index.ts").read_text(encoding="utf-8")
        self.assertIn("actualTempC - sample.predictedTempC", feedback)
        self.assertIn("getVerifiedPredictionErrors", database)
        self.assertIn("cloud.bootstrapComplete", sync)
        self.assertIn("CloudSyncClient.bootstrapAndSync", sync)
        self.assertIn("/v1/bootstrap/feedback/batch", client)
        self.assertIn("cloud_imported_feedback", worker)
        self.assertIn("calculateCorrection", (ROOT / "cloud/src/inference.ts").read_text(encoding="utf-8"))
        self.assertNotIn("predictAndStoreNextHour(", main)
        self.assertIn('cloud_dashboard_$locationKey', main)

    def test_historical_json_is_deleted_only_after_cloud_bootstrap_and_training(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        orchestration = sync.split("fun sync(", 1)[1].split("\n    /**", 1)[0]
        self.assertIn("cloud.bootstrapComplete && cloud.modelReady", orchestration)
        self.assertIn('downloaded.localArchivePath.endsWith(".json"', orchestration)
        self.assertIn("historicalJson.delete()", orchestration)
        self.assertLess(orchestration.index("CloudSyncClient.bootstrapAndSync"), orchestration.index("historicalJson.delete()"))
        self.assertIn("complete historical archive remains cached locally", orchestration)

    def test_archive_retries_rate_limits(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        self.assertIn('code == 429 || code in 500..599', client)
        self.assertIn('getHeaderField("Retry-After")', client)
        self.assertIn('MAX_RETRIES = 4', client)


if __name__ == "__main__":
    unittest.main()
