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
    def test_archive_uses_exact_15_hourly_fields_and_best_match(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        self.assertIn("models=best_match", client)
        self.assertIn("format=csv", client)
        self.assertIn("&hourly=", client)
        self.assertNotIn("&daily=", client)
        self.assertNotIn("past_days=", client)
        start = client.index("private val hourlyFields")
        end = client.index("    )", start)
        selected = re.findall(r'"([a-z0-9_]+)"', client[start:end])
        self.assertEqual(tuple(selected), HOURLY_FIELDS)
        self.assertEqual(len(selected), 15)

    def test_archive_streams_csv_and_preserves_missing_fields(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        self.assertIn("BufferedReader", client)
        self.assertIn("batch.size >= 256", client)
        self.assertIn("numberAt(indices", client)
        self.assertIn("importHourlyArchiveFile", client)
        self.assertIn("downloadHourlyArchiveToFile", client)
        self.assertIn('?: continue', client)

    def test_first_import_is_one_time_then_updates_incrementally(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        self.assertIn("historyCompleteKey", sync)
        self.assertIn("LocalDate.of(2000, 1, 1)", sync)
        self.assertIn("getLatestTimestampSec", sync)
        self.assertIn("OpenMeteoClient.downloadHourlyArchiveToFile", sync)
        self.assertIn("OpenMeteoClient.importHourlyArchiveFile", sync)
        self.assertIn("open_meteo_best_match_2000_present_v2.csv", sync)
        self.assertIn("history_file_complete_v2", sync)
        self.assertIn('KEY_ONE_FILE_ARCHIVE_SETUP_COMPLETE = "one_file_archive_setup_complete_v2"', sync)
        self.assertNotIn("dateChunks", sync)
        self.assertNotIn("fetchHourlyArchive(", sync)
        self.assertIn("fun downloadHistoricalDataset", sync)
        self.assertIn("fun pretrainHistoricalDataset", sync)
        self.assertNotIn("BomClient", sync)
        self.assertNotIn("fetchPreviousDailyContext", sync)

    def test_archive_end_date_is_capped_to_latest_available_utc_day(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        self.assertIn('LocalDate.now(ZoneId.of("UTC")).minusDays(1)', sync)
        self.assertIn("val archiveEndDate = minOf(today, LocalDate.now(ZoneId.of(\"UTC\")).minusDays(1))", sync)
        self.assertEqual(sync.count("start, archiveEndDate,"), 2)
        self.assertIn("minOf(latestDate ?: archiveEndDate, archiveEndDate)", sync)
        self.assertIn("importedToDate = archiveEndDate.toString()", sync)

    def test_reduced_archive_invalidates_models_after_reimport(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        self.assertIn("db.clearModels()", sync)
        self.assertIn('writableDatabase.delete("model_state", null, null)', database)
        self.assertIn("open_meteo_best_match_2000_present_v2.csv", sync)
        self.assertIn("open_meteo_best_match_2000_present.csv", sync)

    def test_database_discards_legacy_provider_data_on_upgrade(self):
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        self.assertIn("DATABASE_VERSION = 12", database)
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

    def test_bom_station_observations_are_separate_from_training_targets(self):
        self.assertTrue((KOTLIN / "BomClient.kt").exists())
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        insert_body = database.split("fun insertReadings", 1)[1].split("fun ", 1)[0]
        self.assertIn("it.temperatureSource == OpenMeteoClient.SOURCE", insert_body)
        activity_path = KOTLIN / "FirstLaunchSetupActivity.kt"
        if activity_path.exists():
            self.assertIn("BOM station", activity_path.read_text(encoding="utf-8"))
        self.assertIn("preferredStationWmoId", (KOTLIN / "BomClient.kt").read_text(encoding="utf-8"))

    def test_first_launch_separates_download_and_pretraining(self):
        activity_path = KOTLIN / "FirstLaunchSetupActivity.kt"
        if activity_path.exists():
            activity = activity_path.read_text(encoding="utf-8")
            self.assertIn("Download historical dataset", activity)
            self.assertIn("one complete CSV file directly to this device", activity)
            self.assertIn("Pre-train with historical dataset", activity)
            self.assertIn("Automatic · nearest suitable reporting station", activity)
            self.assertIn("Manual · choose a station", activity)
            self.assertIn("SyncRepository.downloadHistoricalDataset", activity)
            self.assertIn("SyncRepository.pretrainHistoricalDataset", activity)
        main_activity = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("KEY_ONE_FILE_ARCHIVE_SETUP_COMPLETE", main_activity)
        self.assertIn("KEY_ONE_FILE_ARCHIVE_SETUP_COMPLETE", (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8"))

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

    def test_archive_retries_rate_limits(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        self.assertIn('code == 429 || code in 500..599', client)
        self.assertIn('getHeaderField("Retry-After")', client)
        self.assertIn('MAX_RETRIES = 4', client)


if __name__ == "__main__":
    unittest.main()
