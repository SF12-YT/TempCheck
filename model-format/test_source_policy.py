"""Static regression checks for TempLearn's provider boundaries and location/model wiring."""
from pathlib import Path
import re
import unittest

from reference_predictor import EXPECTED_FEATURES

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/java/com/example/tempalearn"


class SourcePolicyTests(unittest.TestCase):
    def test_open_meteo_request_is_previous_day_daily_only_and_uses_elevation(self):
        client = (KOTLIN / "OpenMeteoClient.kt").read_text(encoding="utf-8")
        self.assertIn("&daily=$dailyFields", client)
        self.assertIn("&past_days=1&forecast_days=0", client)
        self.assertIn("fetchPreviousDailyContext", client)
        self.assertIn("elevation=$it", client)
        self.assertIn("https://api.open-meteo.com/v1/elevation", client)
        self.assertNotIn("hourly=", client)
        self.assertNotIn("HourlyVariables", client)
        self.assertNotIn("temperature_2m", client)
        self.assertNotIn("dew_point_2m", client)
        for field in (
            "sunrise", "sunset", "daylight_duration", "sunshine_duration", "rain_sum",
            "showers_sum", "precipitation_sum", "precipitation_hours",
            "precipitation_probability_max", "wind_direction_10m_dominant",
            "wind_speed_10m_max", "wind_gusts_10m_max", "shortwave_radiation_sum",
            "et0_fao_evapotranspiration",
        ):
            self.assertIn(f'"{field}"', client)

    def test_geocoding_is_primary_location_resolver(self):
        client = (KOTLIN / "LocationGeocodingClient.kt").read_text(encoding="utf-8")
        self.assertIn("geocoding-api.open-meteo.com/v1/search", client)
        for field in ("latitude", "longitude", "elevation", "timezone", "country_code", "admin1"):
            self.assertIn(f'"{field}"', client)
        self.assertIn("Several places match", client)
        self.assertIn("countryCode", client)
        self.assertIn("admin2?.takeIf", client)
        self.assertIn('"USA" to "US"', client)
        self.assertIn('"UK" to "GB"', client)

    def test_sync_uses_one_location_profile_for_provider_requests_and_training(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        self.assertIn("LocationGeocodingClient.resolveUnambiguous", sync)
        self.assertIn("OpenMeteoClient.fetchPreviousDailyContext", sync)
        self.assertIn("BomClient.fetchRecentObservations", sync)
        self.assertIn("profile.latitude", sync)
        self.assertIn("profile.longitude", sync)
        self.assertIn("profile.elevationM", sync)
        self.assertIn("profile.modelContext()", sync)
        self.assertIn("WeatherLearner.trainShared", sync)
        self.assertIn("database.insertReadings(locationKey, bomReadings)", sync)
        self.assertIn("if (savedProfile == null) return@forEach", sync)
        self.assertNotIn("VisualCrossingClient", sync)
        start = sync.index("val bomReadings =")
        end = sync.index("database.insertReadings(locationKey, bomReadings)", start)
        self.assertNotIn("openMeteo", sync[start:end])

    def test_location_history_is_stable_id_keyed_and_migration_preserves_rows(self):
        preferences = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        self.assertIn('fun geocodedLocationKey(geocodingId: Long): String = "geo:$geocodingId"', preferences)
        self.assertIn("fun moveLocationKey", database)
        self.assertIn('db.update(table, values, "location_key=?", arrayOf(fromKey))', database)
        self.assertIn("private const val DATABASE_VERSION = 11", database)
        activity = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("allowLegacyMigration = matches.size == 1", activity)
        self.assertIn("database.saveLocationProfile(profile)", activity)

    def test_location_features_are_part_of_the_learner(self):
        learner = (KOTLIN / "TemperatureLearner.kt").read_text(encoding="utf-8")
        self.assertIn("const val WEIGHT_COUNT = 53", learner)
        for item in ("latitude", "longitude", "elevationM", "hemisphere-season interaction"):
            self.assertIn(item, learner)
        exporter = (KOTLIN / "WeatherModelExport.kt").read_text(encoding="utf-8")
        for item in ("latitude_normalized", "longitude_sin", "longitude_cos", "elevation_normalized", "elevation_present", "latitude_season_sin"):
            self.assertIn(item, exporter)

    def test_exports_include_model_code_and_metadata_assets(self):
        exporter = (KOTLIN / "WeatherModelExport.kt").read_text(encoding="utf-8")
        self.assertIn('"model-package/inference.py"', exporter)
        self.assertIn('"model-package/trainer.py"', exporter)
        self.assertIn("ZipOutputStream", exporter)
        for filename in ("inference.py", "trainer.py"):
            self.assertTrue((ROOT / "app/src/main/assets/model-package" / filename).exists())
        self.assertEqual(
            (ROOT / "app/src/main/assets/model-package/inference.py").read_text(encoding="utf-8"),
            (ROOT / "model-format/reference_predictor.py").read_text(encoding="utf-8"),
        )
        self.assertEqual(
            (ROOT / "app/src/main/assets/model-package/trainer.py").read_text(encoding="utf-8"),
            (ROOT / "model-format/trainer.py").read_text(encoding="utf-8"),
        )
        kotlin_features = re.findall(r'Feature\("([^"]+)"', exporter)
        self.assertEqual(kotlin_features, EXPECTED_FEATURES)

    def test_no_non_bom_weather_is_written_to_historical_training_rows(self):
        sync = (KOTLIN / "SyncRepository.kt").read_text(encoding="utf-8")
        database = (KOTLIN / "TemperatureDatabase.kt").read_text(encoding="utf-8")
        self.assertIn('temperatureSource = "BOM"', sync)
        self.assertIn('"Only BOM air-temperature observations may be stored as training targets."', database)


if __name__ == "__main__":
    unittest.main()
