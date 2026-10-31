package com.example.tempalearn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a Hugging-Face-style, self-contained inference/training package for another application. */
object WeatherModelExport {
    private data class Feature(val name: String, val expression: String, val description: String)

    // This order is a public model contract and must exactly match WeatherLearner.makeFeatures().
    private val features = listOf(
        Feature("bias", "1.0", "Constant intercept."),
        Feature("local_hour_sin", "sin(2*pi*local_hour/24)", "Daily cycle using the target location's local hour."),
        Feature("local_hour_cos", "cos(2*pi*local_hour/24)", "Daily cycle using the target location's local hour."),
        Feature("day_of_year_sin", "sin(2*pi*(day_of_year-1)/days_in_year)", "Annual seasonal cycle; leap years use 366 days."),
        Feature("day_of_year_cos", "cos(2*pi*(day_of_year-1)/days_in_year)", "Annual seasonal cycle; leap years use 366 days."),
        Feature("temperature_t1", "(previous_temperature_1_c-20)/10", "Most recent completed BOM air temperature, Celsius."),
        Feature("temperature_t2", "(previous_temperature_2_c-20)/10", "Air temperature two completed hours before the target."),
        Feature("temperature_t3", "(previous_temperature_3_c-20)/10", "Air temperature three completed hours before the target."),
        Feature("dew_point", "normalize(dew_point_c,20,10)", "Latest completed hour BOM dew point, Celsius."),
        Feature("wind_speed", "normalize(wind_speed_kmh,15,15)", "Latest completed hour BOM wind speed, km/h."),
        Feature("wind_gust", "normalize(wind_gust_kmh,25,25)", "Latest completed hour BOM gust speed, km/h."),
        Feature("mean_sea_level_pressure", "normalize(pressure_hpa,1013,20)", "Latest completed hour BOM mean sea-level pressure, hPa."),
        Feature("relative_humidity", "normalize(humidity_pct,50,50)", "Latest completed hour BOM relative humidity, percent."),
        Feature("cloud_cover_total", "normalize(cloud_cover_pct,50,50)", "Latest completed hour BOM total cloud cover, percent."),
        Feature("precipitation", "normalize(precipitation_mm,0.5,2)", "Latest completed hour BOM precipitation, mm."),
        Feature("shortwave_radiation", "normalize(solar_radiation_wm2,150,300)", "Latest completed hour BOM shortwave radiation, W/m2."),
        Feature("temperature_trend_1", "clamp((t1-t2)/5,-4,4)", "Temperature change between the two most recent observations."),
        Feature("temperature_trend_2", "clamp((t2-t3)/5,-4,4)", "Temperature change between the second and third most recent observations."),
        Feature("solar_daylight_interaction", "solar_normalized*max(0,-cos(hour_angle))", "Solar input weighted by daytime phase."),
        Feature("cloud_daylight_interaction", "cloud_normalized*max(0,-cos(hour_angle))", "Cloud cover weighted by daytime phase."),
        Feature("cloud_cover_low", "normalize(cloud_low_pct,50,50)", "Latest completed hour BOM low cloud cover, percent."),
        Feature("cloud_cover_mid", "normalize(cloud_mid_pct,50,50)", "Latest completed hour BOM mid cloud cover, percent."),
        Feature("cloud_cover_high", "normalize(cloud_high_pct,50,50)", "Latest completed hour BOM high cloud cover, percent."),
        Feature("wind_direction_sin", "wind_direction_present ? sin(radians(direction_deg)) : 0", "Circular encoding of BOM wind direction."),
        Feature("wind_direction_cos", "wind_direction_present ? cos(radians(direction_deg)) : 0", "Circular encoding of BOM wind direction."),
        Feature("vapour_pressure_deficit", "normalize(vapor_pressure_deficit_kpa,1,1)", "Latest completed hour BOM vapour pressure deficit, kPa."),
        Feature("surface_pressure", "normalize(surface_pressure_hpa,1000,20)", "Latest completed hour BOM surface pressure, hPa."),
        Feature("rain", "normalize(rain_mm,0.2,1)", "Latest completed hour BOM rain, mm."),
        Feature("showers", "normalize(showers_mm,0.1,0.5)", "Latest completed hour BOM showers, mm."),
        Feature("snowfall", "normalize(snowfall_cm,0,1)", "Latest completed hour BOM snowfall, cm."),
        Feature("visibility", "normalize(visibility_m,10000,10000)", "Latest completed hour BOM visibility, metres."),
        Feature("evapotranspiration", "normalize(evapotranspiration_mm,0.1,0.5)", "Latest completed hour BOM evapotranspiration, mm."),
        Feature("reference_et0", "normalize(et0_mm,0.1,0.5)", "Latest completed hour BOM reference evapotranspiration, mm."),
        Feature("uv_index", "normalize(uv_index,3,3)", "Latest completed hour BOM UV index."),
        Feature("uv_index_clear_sky", "normalize(uv_index_clear_sky,3,3)", "Latest completed hour BOM clear-sky UV index."),
        Feature("wet_bulb_temperature", "normalize(wet_bulb_temperature_c,20,10)", "Latest completed hour BOM wet-bulb temperature, Celsius."),
        Feature("apparent_temperature", "normalize(apparent_temperature_c,20,10)", "Latest completed hour BOM apparent temperature, Celsius."),
        Feature("shortwave_radiation_instant", "normalize(solar_radiation_instant_wm2,150,300)", "Latest completed hour BOM instantaneous shortwave radiation, W/m2."),
        Feature("weather_clear", "weather_code == 0 ? 1 : 0", "WMO clear-sky category."),
        Feature("weather_partly_cloudy", "weather_code in 1..3 ? 1 : 0", "WMO partly cloudy/cloudy category."),
        Feature("weather_fog", "weather_code in {45,48} ? 1 : 0", "WMO fog category."),
        Feature("weather_drizzle", "weather_code in 51..57 ? 1 : 0", "WMO drizzle/freezing drizzle category."),
        Feature("weather_rain", "weather_code in 61..67 ? 1 : 0", "WMO rain/freezing rain category."),
        Feature("weather_snow", "weather_code in 71..77 or {85,86} ? 1 : 0", "WMO snow/snow shower category."),
        Feature("weather_rain_showers", "weather_code in 80..82 ? 1 : 0", "WMO rain shower category."),
        Feature("weather_thunderstorm", "weather_code in 95..99 ? 1 : 0", "WMO thunderstorm category."),
        Feature("latitude_normalized", "clamp(latitude/90,-1,1)", "Geographic latitude, from -90 south to +90 north."),
        Feature("longitude_sin", "sin(radians(longitude))", "Cyclic longitude encoding; avoids a discontinuity at the date line."),
        Feature("longitude_cos", "cos(radians(longitude))", "Cyclic longitude encoding; avoids a discontinuity at the date line."),
        Feature("elevation_normalized", "normalize(elevation_m,500,1000)", "Location elevation above mean sea level, metres."),
        Feature("elevation_present", "elevation_m is available ? 1 : 0", "Distinguishes unknown elevation from the mean-imputed elevation value."),
        Feature("latitude_season_sin", "latitude_normalized*sin(year_angle)", "Latitude-season interaction helps distinguish hemispheric seasons."),
        Feature("latitude_season_cos", "latitude_normalized*cos(year_angle)", "Latitude-season interaction helps distinguish hemispheric seasons.")
    )

    fun toJson(
        model: LearnedWeatherModel,
        modelScope: String,
        locationLabel: String,
        timezoneId: String,
        defaultLocation: LocationProfile?,
        trainingLocations: List<LocationProfile>
    ): String {
        require(model.weights.size == features.size && features.size == WeatherLearner.WEIGHT_COUNT) {
            "Model feature schema and coefficient count do not match."
        }
        require(model.weights.all { it.isFinite() }) { "Model contains a non-finite coefficient." }
        val featureArray = JSONArray()
        val coefficientArray = JSONArray()
        features.forEachIndexed { index, feature ->
            featureArray.put(JSONObject()
                .put("index", index)
                .put("name", feature.name)
                .put("expression", feature.expression)
                .put("description", feature.description))
            coefficientArray.put(JSONObject()
                .put("index", index)
                .put("feature", feature.name)
                .put("weight", model.weights[index]))
        }
        val weightsArray = JSONArray().apply { model.weights.forEach { put(it) } }
        val priorWeightsArray = model.trainingPriorWeights?.let { prior -> JSONArray().apply { prior.forEach { put(it) } } }
        val trainingLocationsArray = JSONArray().apply {
            trainingLocations.distinctBy { it.locationKey }.forEach { put(locationJson(it)) }
        }
        val training = JSONObject()
            .put("examples", model.trainingExamples)
            .put("validation_examples", model.validationExamples)
            .put("validation_method", "chronological_holdout_last_20_percent_per_location")
            .put("validation_mae_c", finiteJsonNumber(model.validationMaeC))
            .put("validation_bias_c", finiteJsonNumber(model.validationBiasC))
            .put("trained_at_unix_seconds", model.trainedAtSec)
            .put("training_start_unix_seconds", model.trainingStartSec)
            .put("training_end_unix_seconds", model.trainingEndSec)
            .put("contributing_location_count", model.trainedLocationCount)
        val root = JSONObject()
            .put("format", "templearn-weather-model")
            .put("format_version", 2)
            .put("exported_at_unix_seconds", System.currentTimeMillis() / 1_000L)
            .put("model_scope", modelScope)
            .put("model_name", if (modelScope == "shared") "TempLearn Shared Weather Model" else "TempLearn Location Weather Model")
            .put("location_label", locationLabel)
            .put("timezone", timezoneId)
            .put("default_location", defaultLocation?.let(::locationJson) ?: JSONObject.NULL)
            .put("location_profiles", trainingLocationsArray)
            .put("temperature_target", JSONObject()
                .put("name", "next_hour_air_temperature")
                .put("unit", "degrees_celsius")
                .put("sole_observation_source", "BOM"))
            .put("data_provenance", JSONObject()
                .put("BOM", "Exclusive source for historical air-temperature targets, previous-temperature inputs, and historical hourly weather predictors. Missing BOM fields remain missing; no other provider fills them.")
                .put("Open-Meteo Weather", "Previous completed local-day daily context only. The request uses past_days=1 and forecast_days=0, requests only non-temperature daily aggregates, and stores them separately. These daily aggregates are not features in this model.")
                .put("Open-Meteo Geocoding", "Provides place identity, WGS84 coordinates, elevation and IANA timezone. Geographic metadata is used as model features; it is not a weather observation.")
                .put("Open-Meteo Elevation", "The 90 m global DEM endpoint is used only when geocoding does not return an elevation.")
                .put("Visual Crossing", "Not queried by TempLearn 0.7.0; no Visual Crossing values are part of this model.")
                .put("missing_BOM_temperature", "Keep the hour missing. Do not substitute a temperature from another provider."))
            .put("algorithm", JSONObject()
                .put("name", "multivariable_linear_ridge_regression")
                .put("model_architecture", "single linear output with 53 ordered engineered features")
                .put("inference", "clamp(20 + 10 * dot(weights, feature_vector), -90, 70)")
                .put("training_epochs", 250)
                .put("learning_rate", 0.012)
                .put("l2_regularization", 0.012)
                .put("prior_regularization_strength", 0.10)
                .put("local_model_initialization", if (model.trainingPriorWeights != null) "initialised and regularised toward exported training_prior_weights from other locations" else "mean-target intercept; no shared prior"))
            .put("training", training)
            .put("evaluation", JSONObject()
                .put("metric", "mean_absolute_error")
                .put("validation_mae_c", finiteJsonNumber(model.validationMaeC))
                .put("validation_bias_c", finiteJsonNumber(model.validationBiasC))
                .put("validation_examples", model.validationExamples)
                .put("interpretation", "Chronological held-out estimate; not a guarantee of future forecast performance."))
            .put("output_transform", JSONObject()
                .put("base_temperature_c", 20.0)
                .put("temperature_scale_c", 10.0)
                .put("minimum_temperature_c", -90.0)
                .put("maximum_temperature_c", 70.0)
                .put("formula", "clamp(20 + 10 * dot(weights, features), -90, 70)"))
            .put("inference_contract", JSONObject()
                .put("target_timestamp", "Unix seconds for the local hour being predicted; convert using the target location's IANA timezone.")
                .put("previous_temperatures_c", JSONArray()
                    .put("latest completed BOM hour t1")
                    .put("one completed hour before t1 t2")
                    .put("two completed hours before t1 t3"))
                .put("geographic_inputs", "Latitude and longitude in WGS84 degrees plus elevation above mean sea level in metres. Shared models require target-location coordinates and timezone at inference. Location models default to the exported location metadata.")
                .put("weather_inputs", "Historical weather values from the latest completed BOM observation hour t1. All fields must come from that BOM station observation; missing fields stay missing. Open-Meteo daily context is not an input to this trained model.")
                .put("required_temperature_history", "Three consecutive hourly BOM observations. Do not interpolate missing BOM temperatures.")
                .put("air_temperature_source_policy", "BOM is the sole source of air-temperature targets and previous-temperature inputs. Never substitute Open-Meteo, Visual Crossing, or another modelled temperature.")
                .put("other_weather_source_policy", "BOM is the only source for historical weather predictors used in training and inference. Never fill a missing historical BOM field with Open-Meteo data.")
                .put("missing_weather_values", "After centring and scaling, missing numeric weather features are set to 0.0. Missing wind direction produces sin=0 and cos=0. Unknown elevation uses normalized zero plus elevation_present=0."))
            .put("timezone_policy", if (modelScope == "shared") {
                "Pass the target location's IANA timezone at inference; training used each contributing location's own timezone. The exported timezone is the active app location's default only."
            } else {
                "Use the exported IANA timezone for local hour and day-of-year features."
            })
            .put("normalisation", JSONObject()
                .put("numeric", "clamp((value-center)/scale, -4, 4); missing numeric values become 0")
                .put("temperature_baseline_c", 20.0)
                .put("temperature_scale_c", 10.0)
                .put("latitude", "latitude/90, clamped to [-1,1]")
                .put("longitude", "sin and cos of longitude in radians")
                .put("elevation", "clamp((elevation_m-500)/1000,-4,4); separate availability indicator")
                .put("wind_direction", "convert degrees to radians then use sine and cosine"))
            .put("feature_count", features.size)
            .put("feature_schema", featureArray)
            .put("weights", weightsArray)
            .put("coefficients", coefficientArray)
            .put("training_prior_weights", priorWeightsArray ?: JSONObject.NULL)
        return root.toString(2)
    }

    fun toZip(
        context: Context,
        model: LearnedWeatherModel,
        modelScope: String,
        locationLabel: String,
        timezoneId: String,
        defaultLocation: LocationProfile?,
        trainingLocations: List<LocationProfile>
    ): ByteArray {
        val modelJson = JSONObject(toJson(model, modelScope, locationLabel, timezoneId, defaultLocation, trainingLocations))
        val schemaJson = JSONObject()
            .put("schema_version", 1)
            .put("feature_count", features.size)
            .put("features", modelJson.getJSONArray("feature_schema"))
        val packageReadme = """# ${modelJson.getString("model_name")}

TempLearn portable model package, format v2. This archive contains the complete learned inference state and executable reference implementation; it does not require the TempLearn Android app, its SQLite database, API keys, or network access for inference.

## Contents

- `model.json` — architecture description, all ${features.size} ordered feature definitions, learned coefficients, preprocessing rules, source policy, training coverage, saved location profiles, and validation metrics. `training.contributing_location_count` is the number of locations actually used for fitting.
- `feature_schema.json` — machine-readable ordered feature schema.
- `inference.py` — standard-library Python inference implementation with a command-line interface.
- `trainer.py` — standard-library reference ridge-regression trainer for new BOM JSONL datasets.
- `inference_contract.json` — required input and provenance contract.
- `training_metadata.json` and `evaluation.json` — learning coverage, local profile metadata and held-out evaluation.
- `MODEL_CARD.md` — human-readable model card, intended use and limitations.
- `requirements.txt` — runtime requirements.

## Quick inference

Python 3.10 or newer is required. Inference uses only the Python standard library.

1. Create `request.json` using the structure shown in `MODEL_CARD.md`.
2. Run `python inference.py model.json request.json`.
3. The script prints a JSON object containing `prediction_c`.

A location-specific model can use its embedded coordinates, elevation and timezone when those fields are omitted. A shared model requires the target location's latitude, longitude, elevation (optional) and IANA timezone in the request.

## Model architecture

A single-output multivariable linear ridge-regression model. The learned state is a vector of ${features.size} coefficients. The inference program builds the exact feature vector, applies those coefficients and converts the normalized output back to Celsius. `trainer.py` documents and implements the reference fitting procedure.

## Training data and source boundaries

Historical hourly targets and historical weather inputs are BOM-only. Missing BOM temperatures are never filled by another provider; missing historical BOM predictor fields remain missing. Open-Meteo daily aggregates are stored separately and are not model features. Open-Meteo Geocoding/Elevation supply location metadata only.

The ZIP deliberately excludes raw hourly records and API credentials. Stored location profiles are included as geographic metadata; some profiles may not have had enough consecutive BOM samples to contribute to this particular fit. `training.contributing_location_count` records the actual contributing count. The shared model combines only this installation's saved, geocoded locations; it does not pool data from other users or a remote server.

## Evaluation caveat

Validation metrics use a chronological held-out portion of the stored examples. They are useful diagnostics, not a guarantee of real-world next-hour forecast accuracy. Performance depends on station coverage, missing fields, weather regimes and geographic representativeness.
"""
        val inferenceContract = modelJson.getJSONObject("inference_contract").toString(2)
        val trainingMetadata = JSONObject()
            .put("metadata_version", 1)
            .put("model_scope", modelScope)
            .put("training", modelJson.getJSONObject("training"))
            .put("location_profiles", modelJson.getJSONArray("location_profiles"))
            .toString(2)
        val evaluation = modelJson.getJSONObject("evaluation").toString(2)
        val trainerScript = context.assets.open("model-package/trainer.py").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val inferenceScript = context.assets.open("model-package/inference.py").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val modelCard = """# Model card

## Summary

**Name:** ${modelJson.getString("model_name")}  
**Scope:** $modelScope  
**Format:** TempLearn model v2  
**Prediction:** next-hour air temperature (°C)  
**Training examples:** ${model.trainingExamples}  
**Contributing locations:** ${model.trainedLocationCount}  
**Training interval:** ${formatUnix(model.trainingStartSec)} to ${formatUnix(model.trainingEndSec)}

## Intended use

Experimental short-horizon temperature estimation and algorithm research. It is not an official forecast, safety instrument, or substitute for a national weather service.

## Evaluation

- Chronological held-out MAE: ${finiteText(model.validationMaeC)} °C
- Held-out signed bias: ${finiteText(model.validationBiasC)} °C
- Held-out examples: ${model.validationExamples}

## Inputs

Three consecutive previous hourly BOM temperatures, BOM weather values for the latest completed hour, target timestamp, local IANA timezone, latitude, longitude and optional elevation.

## Limitations

A linear ridge model cannot represent every nonlinear atmospheric process. The on-device shared model's geographical generalisation is limited by the distribution and representativeness of the locations saved on this phone; it does not pool data from other users. The package contains no raw training observations. No guarantee is made that the validation score will persist in future conditions.

## Data provenance

BOM is the only historical observation provider. Open-Meteo daily context is not a model input. Open-Meteo Geocoding provides geographic metadata. See `model.json` for the machine-readable source policy.

## Example request

```json
{
  "target_timestamp_unix_seconds": 1800003600,
  "timezone": "Australia/Sydney",
  "location": {"latitude": -33.7198, "longitude": 150.3074, "elevation_m": 1000},
  "previous_bom_hours": [
    {"timestamp_unix_seconds": 1800000000, "temperature_c": 21.8},
    {"timestamp_unix_seconds": 1799996400, "temperature_c": 21.2},
    {"timestamp_unix_seconds": 1799992800, "temperature_c": 20.7}
  ],
  "weather": {"humidity_pct": 55, "wind_speed_kmh": 12, "weather_code": 2}
}
```

Missing weather fields may be omitted. Do not substitute non-BOM historical weather values.
"""
        val requirements = "Python>=3.10\n# Inference and reference trainer use the Python standard library only.\n"
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            addText(zip, "model.json", modelJson.toString(2))
            addText(zip, "feature_schema.json", schemaJson.toString(2))
            addText(zip, "inference_contract.json", inferenceContract)
            addText(zip, "training_metadata.json", trainingMetadata)
            addText(zip, "evaluation.json", evaluation)
            addText(zip, "README.md", packageReadme)
            addText(zip, "MODEL_CARD.md", modelCard)
            addText(zip, "requirements.txt", requirements)
            addText(zip, "inference.py", inferenceScript)
            addText(zip, "trainer.py", trainerScript)
        }
        return output.toByteArray()
    }

    private fun locationJson(profile: LocationProfile): JSONObject = JSONObject()
        .put("geocoding_id", profile.geocodingId)
        .put("search_query", profile.searchQuery)
        .put("display_name", profile.displayName)
        .put("name", profile.name)
        .put("admin1", profile.admin1 ?: JSONObject.NULL)
        .put("admin2", profile.admin2 ?: JSONObject.NULL)
        .put("country", profile.country ?: JSONObject.NULL)
        .put("country_code", profile.countryCode ?: JSONObject.NULL)
        .put("latitude", profile.latitude)
        .put("longitude", profile.longitude)
        .put("elevation_m", profile.elevationM ?: JSONObject.NULL)
        .put("timezone", profile.timezoneId)
        .put("feature_code", profile.featureCode ?: JSONObject.NULL)
        .put("population", profile.population ?: JSONObject.NULL)
        .put("postcodes", JSONArray(profile.postcodes))
        .put("resolved_at_unix_seconds", profile.resolvedAtSec)

    private fun addText(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun finiteJsonNumber(value: Double): Any = if (value.isFinite()) value else JSONObject.NULL
    private fun finiteText(value: Double): String = if (value.isFinite()) "%.3f".format(java.util.Locale.ROOT, value) else "not available"
    private fun formatUnix(seconds: Long): String = if (seconds <= 0L) "unknown" else java.time.Instant.ofEpochSecond(seconds).toString()
}
