import unittest

from reference_predictor import EXPECTED_FEATURES, build_feature_vector, predict_next_hour


class ReferencePredictorTests(unittest.TestCase):
    def setUp(self):
        self.latest = 1_800_000_000 // 3600 * 3600
        self.history = [
            {"timestamp_unix_seconds": self.latest, "temperature_c": 21.8},
            {"timestamp_unix_seconds": self.latest - 3600, "temperature_c": 21.2},
            {"timestamp_unix_seconds": self.latest - 7200, "temperature_c": 20.7},
        ]
        self.location = {"latitude": -33.7198, "longitude": 150.3074, "elevation_m": 1000.0}
        self.model = {
            "format": "templearn-weather-model",
            "format_version": 2,
            "model_scope": "location",
            "timezone": "Australia/Sydney",
            "default_location": self.location,
            "weights": [0.0] * 53,
            "feature_count": 53,
            "feature_schema": [{"index": index, "name": name} for index, name in enumerate(EXPECTED_FEATURES)],
        }

    def test_feature_count_and_finite_values(self):
        vector = build_feature_vector(
            self.latest + 3600,
            self.history,
            {"wind_direction_deg": 270, "cloud_cover_pct": 50, "weather_code": 95},
            "Australia/Sydney",
            self.location,
        )
        self.assertEqual(len(vector), 53)
        self.assertTrue(all(isinstance(value, float) for value in vector))

    def test_zero_weights_return_baseline(self):
        value = predict_next_hour(self.model, self.latest + 3600, self.history, {})
        self.assertEqual(value, 20.0)

    def test_geography_changes_feature_vector(self):
        a = build_feature_vector(self.latest + 3600, self.history, {}, "Australia/Sydney", self.location)
        b = build_feature_vector(
            self.latest + 3600,
            self.history,
            {},
            "Australia/Sydney",
            {"latitude": 51.5072, "longitude": -0.1276, "elevation_m": 11},
        )
        self.assertNotEqual(a[46:], b[46:])
        self.assertEqual(a[50], 1.0)

    def test_missing_elevation_has_explicit_flag(self):
        vector = build_feature_vector(
            self.latest + 3600,
            self.history,
            {},
            "Australia/Sydney",
            {"latitude": -33.7, "longitude": 150.3},
        )
        self.assertEqual(vector[49], 0.0)
        self.assertEqual(vector[50], 0.0)

    def test_shared_model_requires_target_location_and_timezone(self):
        shared = dict(self.model)
        shared["model_scope"] = "shared"
        shared["timezone"] = None
        shared["default_location"] = None
        with self.assertRaises(ValueError):
            predict_next_hour(shared, self.latest + 3600, self.history, {})

    def test_rejects_nonconsecutive_bom_history(self):
        broken = [self.history[0], self.history[1], {
            "timestamp_unix_seconds": self.latest - 5400,
            "temperature_c": 20.7,
        }]
        with self.assertRaises(ValueError):
            predict_next_hour(self.model, self.latest + 3600, broken, {})

    def test_rejects_reordered_feature_schema(self):
        broken = dict(self.model)
        broken["feature_schema"] = list(reversed(self.model["feature_schema"]))
        with self.assertRaises(ValueError):
            predict_next_hour(broken, self.latest + 3600, self.history, {})

    def test_rejects_invalid_coordinates(self):
        with self.assertRaises(ValueError):
            build_feature_vector(
                self.latest + 3600,
                self.history,
                {},
                "Australia/Sydney",
                {"latitude": 120, "longitude": 150},
            )


if __name__ == "__main__":
    unittest.main()
