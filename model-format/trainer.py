"""Reference standard-library trainer for TempLearn model format v2.

Input is JSON Lines: one BOM hourly observation per line. See MODEL_FORMAT.md.
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path
from typing import Any

try:
    from inference import EXPECTED_FEATURES, build_feature_vector
except ImportError:  # Repo-local test/development name
    from reference_predictor import EXPECTED_FEATURES, build_feature_vector

BASE_TEMP_C = 20.0
TEMP_SCALE_C = 10.0
EPOCHS = 250
LEARNING_RATE = 0.012
L2 = 0.012
PRIOR_STRENGTH = 0.10
MAX_EXAMPLES_PER_LOCATION = 4000


def _dot(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


def _fit(examples: list[tuple[list[float], float]], prior: list[float] | None) -> list[float]:
    weights = [0.0] * len(EXPECTED_FEATURES)
    if prior is not None and len(prior) == len(EXPECTED_FEATURES):
        weights = [float(value) for value in prior]
    elif examples:
        weights[0] = sum(target for _, target in examples) / len(examples)
    if not examples:
        return weights
    divisor = float(len(examples))
    for _ in range(EPOCHS):
        gradient = [0.0] * len(weights)
        for vector, target in examples:
            error = _dot(weights, vector) - target
            for index, value in enumerate(vector):
                gradient[index] += (2.0 * error * value) / divisor
        for index in range(len(weights)):
            if index != 0:
                gradient[index] += 2.0 * L2 * weights[index]
            if prior is not None and len(prior) == len(weights):
                gradient[index] += 2.0 * PRIOR_STRENGTH * (weights[index] - prior[index])
            weights[index] -= LEARNING_RATE * gradient[index]
    return weights


def _limit(items: list[Any]) -> list[Any]:
    if len(items) <= MAX_EXAMPLES_PER_LOCATION:
        return items
    step = len(items) / MAX_EXAMPLES_PER_LOCATION
    return [items[math.floor(index * step)] for index in range(MAX_EXAMPLES_PER_LOCATION)]


def _read_examples(path: Path) -> tuple[list[tuple[list[float], float]], list[tuple[list[float], float]], int, int, int]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSON on line {line_number}: {exc}") from exc
        if str(row.get("temperature_source", "")).strip().upper() != "BOM":
            raise ValueError(f'Line {line_number} must explicitly declare temperature_source=\"BOM\"; mixed-source training is prohibited.')
        if str(row.get("weather_source", "")).strip().upper() != "BOM":
            raise ValueError(f'Line {line_number} must explicitly declare weather_source=\"BOM\"; mixed-provider predictors are prohibited.')
        grouped.setdefault(str(row.get("location_key", "default")), []).append(row)

    training: list[tuple[list[float], float]] = []
    validation: list[tuple[list[float], float]] = []
    starts: list[int] = []
    ends: list[int] = []
    contributing = 0
    for rows in grouped.values():
        rows.sort(key=lambda row: int(row["timestamp_unix_seconds"]))
        timestamps = [int(row["timestamp_unix_seconds"]) for row in rows]
        if timestamps:
            starts.append(min(timestamps))
            ends.append(max(timestamps))
        generated: list[tuple[list[float], float]] = []
        for index in range(3, len(rows)):
            previous = rows[index - 3:index]
            target = rows[index]
            if any(int(b["timestamp_unix_seconds"]) - int(a["timestamp_unix_seconds"]) != 3600 for a, b in zip(previous, previous[1:])):
                continue
            if int(target["timestamp_unix_seconds"]) - int(previous[-1]["timestamp_unix_seconds"]) != 3600:
                continue
            location = {
                "latitude": target.get("latitude", previous[-1].get("latitude")),
                "longitude": target.get("longitude", previous[-1].get("longitude")),
                "elevation_m": target.get("elevation_m", previous[-1].get("elevation_m")),
            }
            timezone = str(target.get("timezone") or previous[-1].get("timezone") or "").strip()
            if not timezone:
                raise ValueError(f"Location {target.get('location_key', 'default')} is missing its IANA timezone.")
            weather = previous[-1].get("weather", {})
            vector = build_feature_vector(
                target_timestamp_unix_seconds=int(target["timestamp_unix_seconds"]),
                previous_bom_hours=[
                    {"timestamp_unix_seconds": int(item["timestamp_unix_seconds"]), "temperature_c": item["temperature_c"]}
                    for item in reversed(previous)
                ],
                weather=weather,
                timezone_id=timezone,
                location=location,
            )
            temperature = float(target["temperature_c"])
            generated.append((vector, (temperature - BASE_TEMP_C) / TEMP_SCALE_C))
        generated = _limit(generated)
        if len(generated) < 8:
            continue
        contributing += 1
        split = min(max(int(len(generated) * 0.8), 1), len(generated) - 1)
        training.extend(generated[:split])
        validation.extend(generated[split:])

    if len(training) < 5 or not validation:
        raise ValueError("Not enough complete consecutive BOM hours to train and validate this model.")
    return training, validation, contributing, min(starts, default=0), max(ends, default=0)


def _metrics(examples: list[tuple[list[float], float]], weights: list[float]) -> tuple[float, float]:
    errors = [(_dot(weights, vector) * TEMP_SCALE_C + BASE_TEMP_C) - (target * TEMP_SCALE_C + BASE_TEMP_C)
              for vector, target in examples]
    return sum(abs(error) for error in errors) / len(errors), sum(errors) / len(errors)


def train_model(model_path: Path, data_path: Path, output_path: Path) -> None:
    model = json.loads(model_path.read_text(encoding="utf-8"))
    if model.get("format") != "templearn-weather-model" or model.get("format_version") != 2:
        raise ValueError("Expected a TempLearn model format v2 model.json.")
    if model.get("feature_count") != len(EXPECTED_FEATURES):
        raise ValueError("Model feature_count does not match the v2 schema.")
    schema = model.get("feature_schema")
    if not isinstance(schema, list) or len(schema) != len(EXPECTED_FEATURES):
        raise ValueError("Model feature_schema is missing, reordered or incompatible.")
    if any(not isinstance(item, dict) for item in schema):
        raise ValueError("Model feature_schema entries must be objects.")
    if [item.get("name") for item in schema] != EXPECTED_FEATURES or [item.get("index") for item in schema] != list(range(len(EXPECTED_FEATURES))):
        raise ValueError("Model feature_schema is missing, reordered or incompatible.")
    weights = model.get("weights")
    if not isinstance(weights, list) or len(weights) != len(EXPECTED_FEATURES):
        raise ValueError("Model does not contain exactly 53 fitted coefficients.")
    training, validation, contributing, start, end = _read_examples(data_path)
    prior = model.get("training_prior_weights")
    if prior is not None:
        if not isinstance(prior, list) or len(prior) != len(EXPECTED_FEATURES):
            raise ValueError("training_prior_weights does not match the v2 feature schema.")
        try:
            prior = [float(value) for value in prior]
        except (TypeError, ValueError) as exc:
            raise ValueError("training_prior_weights must contain finite numbers.") from exc
        if any(not math.isfinite(value) for value in prior):
            raise ValueError("training_prior_weights must contain finite numbers.")
    validation_weights = _fit(training, prior)
    mae, bias = _metrics(validation, validation_weights)
    all_examples = training + validation
    final_weights = _fit(all_examples, prior)
    model["weights"] = final_weights
    model["coefficients"] = [
        {"index": index, "feature": name, "weight": final_weights[index]}
        for index, name in enumerate(EXPECTED_FEATURES)
    ]
    model["training"] = {
        **model.get("training", {}),
        "examples": len(all_examples),
        "validation_examples": len(validation),
        "validation_method": "chronological_holdout_last_20_percent_per_location",
        "validation_mae_c": mae,
        "validation_bias_c": bias,
        "training_start_unix_seconds": start,
        "training_end_unix_seconds": end,
        "contributing_location_count": contributing,
        "input_temperature_source": "BOM",
        "input_feature_source": "BOM",
    }
    model["evaluation"] = {
        "metric": "mean_absolute_error",
        "validation_mae_c": mae,
        "validation_bias_c": bias,
        "validation_examples": len(validation),
        "interpretation": "Chronological held-out estimate; not a guarantee of future forecast performance.",
    }
    output_path.write_text(json.dumps(model, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 4:
        print("Usage: python trainer.py model.json bom_training.jsonl updated_model.json", file=sys.stderr)
        return 2
    train_model(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
