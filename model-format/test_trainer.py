"""Integration tests for the portable, standard-library trainer."""
from pathlib import Path
import json
import tempfile
import unittest

from trainer import train_model

ROOT = Path(__file__).resolve().parents[1]


def base_model():
    from reference_predictor import EXPECTED_FEATURES
    return {
        "format": "templearn-weather-model",
        "format_version": 3,
        "feature_count": len(EXPECTED_FEATURES),
        "feature_schema": [{"index": i, "name": name} for i, name in enumerate(EXPECTED_FEATURES)],
        "weights": [0.0] * len(EXPECTED_FEATURES),
        "model_scope": "shared",
        "training_prior_weights": None,
        "training": {},
    }


def rows(source="OPEN_METEO_BEST_MATCH"):
    result = []
    start = 1_800_000_000
    for i in range(20):
        result.append({
            "location_key": "geo:1",
            "timestamp_unix_seconds": start + i * 3600,
            "temperature_c": 12.0 + 0.3 * i,
            "temperature_source": source,
            "weather_source": source,
            "timezone": "Australia/Sydney",
            "latitude": -33.7,
            "longitude": 150.3,
            "elevation_m": 1000.0,
            "weather": {"humidity_pct": 60, "wind_speed_kmh": 10},
        })
    return result


class TrainerTests(unittest.TestCase):
    def test_trainer_produces_complete_v2_coefficients_and_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            model = folder / "model.json"
            data = folder / "open_meteo.jsonl"
            output = folder / "updated.json"
            model.write_text(json.dumps(base_model()), encoding="utf-8")
            data.write_text("\n".join(json.dumps(row) for row in rows()) + "\n", encoding="utf-8")
            train_model(model, data, output)
            result = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(result["feature_count"], 62)
            self.assertEqual(len(result["weights"]), 62)
            self.assertEqual(len(result["coefficients"]), 62)
            self.assertGreater(result["training"]["examples"], 0)
            self.assertEqual(result["training"]["contributing_location_count"], 1)
            self.assertIn("validation_mae_c", result["evaluation"])

    def test_trainer_counts_distinct_geocoded_locations(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            model = folder / "model.json"
            data = folder / "open_meteo.jsonl"
            output = folder / "updated.json"
            model.write_text(json.dumps(base_model()), encoding="utf-8")
            combined = rows() + rows()
            for index, row in enumerate(combined):
                if index >= 20:
                    row["location_key"] = "geo:2"
                    row["timestamp_unix_seconds"] = 1_800_000_000 + (index - 20) * 3600
                    row["temperature_c"] += 4.0
                    row["latitude"] = -31.95
                    row["longitude"] = 115.86
                    row["elevation_m"] = 20.0
            data.write_text("\n".join(json.dumps(row) for row in combined) + "\n", encoding="utf-8")
            train_model(model, data, output)
            result = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(result["training"]["contributing_location_count"], 2)
            self.assertEqual(result["training"]["examples"], 34)

    def test_trainer_rejects_non_open_meteo_temperature_source(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            model = folder / "model.json"
            data = folder / "open_meteo.jsonl"
            output = folder / "updated.json"
            model.write_text(json.dumps(base_model()), encoding="utf-8")
            invalid = rows(source="OTHER")
            data.write_text("\n".join(json.dumps(row) for row in invalid) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "temperature_source"):
                train_model(model, data, output)

    def test_trainer_rejects_non_open_meteo_weather_source(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            model = folder / "model.json"
            data = folder / "open_meteo.jsonl"
            output = folder / "updated.json"
            model.write_text(json.dumps(base_model()), encoding="utf-8")
            invalid = rows(source="BOM")
            for row in invalid:
                row["weather_source"] = "OTHER"
            data.write_text("\n".join(json.dumps(row) for row in invalid) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "weather_source"):
                train_model(model, data, output)

    def test_trainer_rejects_unsupported_feature_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            model = folder / "model.json"
            data = folder / "open_meteo.jsonl"
            output = folder / "updated.json"
            invalid = base_model()
            invalid["feature_schema"][4]["name"] = "wrong_feature"
            model.write_text(json.dumps(invalid), encoding="utf-8")
            data.write_text("\n".join(json.dumps(row) for row in rows()) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "feature_schema"):
                train_model(model, data, output)


if __name__ == "__main__":
    unittest.main()
