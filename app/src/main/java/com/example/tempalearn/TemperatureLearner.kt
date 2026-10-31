package com.example.tempalearn

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

data class ModelLocationContext(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val elevationM: Double? = null
)

/**
 * Small multivariable ridge-regression model trained entirely on the phone.
 *
 * It predicts next-hour temperature from time/season, recent temperature changes, and the most
 * recent hour's atmospheric, cloud, precipitation, radiation and wind variables. WMO weather
 * codes are encoded as condition groups instead of being treated as ordered numeric values.
 * A shared model is trained across eligible, geocoded locations saved on this device, while each
 * location gets a locally adapted model
 * regularised toward a model learned from *other* locations. This gives new locations a useful
 * prior without mixing their stored reading histories.
 */
object WeatherLearner {
    const val WEIGHT_COUNT = 62
    private const val BASE_TEMP_C = 20.0
    private const val TEMP_SCALE_C = 10.0
    private const val MAX_EXAMPLES_PER_LOCATION = 4_000
    private const val EPOCHS = 250
    private const val LEARNING_RATE = 0.012
    private const val L2 = 0.012
    private const val PRIOR_STRENGTH = 0.10

    private data class Example(val features: DoubleArray, val targetNormalized: Double, val actualC: Double)
    private data class SeriesExamples(
        val all: List<Example>,
        val training: List<Example>,
        val validation: List<Example>,
        val startSec: Long,
        val endSec: Long
    )
    private data class ErrorStats(val maeC: Double, val biasC: Double, val count: Int)

    /** Train and evaluate a local model, optionally starting from shared weights learned elsewhere. */
    fun trainLocal(
        readings: List<HourlyTemperature>,
        timezoneId: String,
        priorModel: LearnedWeatherModel? = null,
        trainedAtSec: Long = Instant.now().epochSecond,
        locationContext: ModelLocationContext = ModelLocationContext()
    ): LearnedWeatherModel? {
        val zone = zone(timezoneId)
        val examples = limitExamples(buildExamples(readings, zone, locationContext))
        if (examples.size < 8) return null

        val split = (examples.size * 0.8).toInt().coerceIn(1, examples.lastIndex)
        val training = examples.subList(0, split)
        val validation = examples.subList(split, examples.size)
        val prior = priorModel?.weights?.takeIf { it.size == WEIGHT_COUNT }?.toDoubleArray()
        val validationWeights = fitWeights(training, prior)
        val stats = evaluate(validation, validationWeights)
        val finalWeights = fitWeights(examples, prior)
        return LearnedWeatherModel(
            weights = finalWeights.toList(),
            trainingExamples = examples.size,
            validationMaeC = stats.maeC,
            validationBiasC = stats.biasC,
            validationExamples = stats.count,
            trainedAtSec = trainedAtSec,
            trainingStartSec = readings.minOfOrNull { it.timestampSec } ?: 0L,
            trainingEndSec = readings.maxOfOrNull { it.timestampSec } ?: 0L,
            trainedLocationCount = 1,
            trainingPriorWeights = prior?.toList()
        )
    }

    /** Train a shared model across several location histories with geographic features per series. */
    fun trainShared(
        series: Map<String, Pair<List<HourlyTemperature>, String>>,
        trainedAtSec: Long = Instant.now().epochSecond,
        locationContexts: Map<String, ModelLocationContext> = emptyMap()
    ): LearnedWeatherModel? {
        val prepared = series.mapNotNull { (locationKey, value) ->
            val (readings, timezoneId) = value
            val examples = limitExamples(buildExamples(readings, zone(timezoneId), locationContexts[locationKey] ?: ModelLocationContext()))
            if (examples.size < 8) null else {
                val split = (examples.size * 0.8).toInt().coerceIn(1, examples.lastIndex)
                SeriesExamples(
                    all = examples,
                    training = examples.subList(0, split),
                    validation = examples.subList(split, examples.size),
                    startSec = readings.minOfOrNull { it.timestampSec } ?: 0L,
                    endSec = readings.maxOfOrNull { it.timestampSec } ?: 0L
                )
            }
        }
        val allExamples = prepared.flatMap { it.all }
        val trainingExamples = prepared.flatMap { it.training }
        val validationExamples = prepared.flatMap { it.validation }
        if (allExamples.size < 8 || trainingExamples.size < 5 || validationExamples.isEmpty()) return null

        val validationWeights = fitWeights(trainingExamples, null)
        val stats = evaluate(validationExamples, validationWeights)
        val finalWeights = fitWeights(allExamples, null)
        return LearnedWeatherModel(
            weights = finalWeights.toList(),
            trainingExamples = allExamples.size,
            validationMaeC = stats.maeC,
            validationBiasC = stats.biasC,
            validationExamples = stats.count,
            trainedAtSec = trainedAtSec,
            trainingStartSec = prepared.minOfOrNull { it.startSec } ?: 0L,
            trainingEndSec = prepared.maxOfOrNull { it.endSec } ?: 0L,
            trainedLocationCount = prepared.size,
            trainingPriorWeights = null
        )
    }

    /** Predicts the next hourly temperature using the preceding three consecutive observations. */
    fun predictNext(
        readings: List<HourlyTemperature>,
        model: LearnedWeatherModel,
        timezoneId: String,
        locationContext: ModelLocationContext = ModelLocationContext()
    ): Pair<Long, Double>? {
        if (readings.size < 3 || model.weights.size != WEIGHT_COUNT) return null
        val recent = readings.takeLast(3)
        if (recent.zipWithNext().any { (a, b) -> b.timestampSec - a.timestampSec != 3_600L }) return null
        val nextTimestamp = readings.last().timestampSec + 3_600L
        val features = makeFeatures(nextTimestamp, recent.reversed(), zone(timezoneId), locationContext)
        val predicted = (dot(model.weights.toDoubleArray(), features) * TEMP_SCALE_C + BASE_TEMP_C)
            .coerceIn(-90.0, 70.0)
        return nextTimestamp to predicted
    }

    private fun buildExamples(
        readings: List<HourlyTemperature>,
        zone: ZoneId,
        locationContext: ModelLocationContext
    ): List<Example> {
        if (readings.size < 4) return emptyList()
        val examples = ArrayList<Example>()
        for (index in 3 until readings.size) {
            val window = readings.subList(index - 3, index + 1)
            if (window.zipWithNext().any { (a, b) -> b.timestampSec - a.timestampSec != 3_600L }) continue
            val target = window.last()
            val previousLatestFirst = listOf(window[2], window[1], window[0])
            examples += Example(
                features = makeFeatures(target.timestampSec, previousLatestFirst, zone, locationContext),
                targetNormalized = (target.temperatureC - BASE_TEMP_C) / TEMP_SCALE_C,
                actualC = target.temperatureC
            )
        }
        return examples
    }

    private fun limitExamples(examples: List<Example>): List<Example> {
        if (examples.size <= MAX_EXAMPLES_PER_LOCATION) return examples
        // Evenly sample the entire history rather than keeping only recent months; this preserves
        // more seasonal variety after the app has collected a full year or more.
        val step = examples.size.toDouble() / MAX_EXAMPLES_PER_LOCATION
        return List(MAX_EXAMPLES_PER_LOCATION) { index -> examples[floor(index * step).toInt()] }
    }

    private fun makeFeatures(
        targetTimestampSec: Long,
        previous: List<HourlyTemperature>,
        zone: ZoneId,
        locationContext: ModelLocationContext
    ): DoubleArray {
        val localTime = Instant.ofEpochSecond(targetTimestampSec).atZone(zone)
        val hourAngle = 2.0 * PI * localTime.hour / 24.0
        val daysInYear = localTime.toLocalDate().lengthOfYear()
        val yearAngle = 2.0 * PI * (localTime.dayOfYear - 1).toDouble() / daysInYear
        val latest = previous[0]
        // The day/night interaction lets the same solar/cloud values mean different things at
        // noon versus midnight. Recent deltas capture a fast warm-up or cool-down, not just levels.
        val daylightFactor = maxOf(0.0, -cos(hourAngle))
        val solarNormalized = latest.solarRadiationWm2.normalized(center = 150.0, scale = 300.0)
        val cloudNormalized = latest.cloudCoverPct.normalized(center = 50.0, scale = 50.0)
        val directionAngle = Math.toRadians(latest.windDirectionDeg ?: 0.0)
        val weatherCode = latest.weatherCode
        return doubleArrayOf(
            1.0, // 1 bias
            sin(hourAngle), // 2-3 daily cycle
            cos(hourAngle),
            sin(yearAngle), // 4-5 seasonal cycle
            cos(yearAngle),
            (previous[0].temperatureC - BASE_TEMP_C) / TEMP_SCALE_C, // 6-8 recent temperatures
            (previous[1].temperatureC - BASE_TEMP_C) / TEMP_SCALE_C,
            (previous[2].temperatureC - BASE_TEMP_C) / TEMP_SCALE_C,
            latest.dewPointC.normalized(center = BASE_TEMP_C, scale = TEMP_SCALE_C), // 9
            latest.windSpeedKmh.normalized(center = 15.0, scale = 15.0), // 10
            latest.windGustKmh.normalized(center = 25.0, scale = 25.0), // 11
            latest.pressureHpa.normalized(center = 1013.0, scale = 20.0), // 12
            latest.humidityPct.normalized(center = 50.0, scale = 50.0), // 13
            cloudNormalized, // 14
            latest.precipitationMm.normalized(center = 0.5, scale = 2.0), // 15
            solarNormalized, // 16
            ((previous[0].temperatureC - previous[1].temperatureC) / 5.0).coerceIn(-4.0, 4.0), // 17 trend
            ((previous[1].temperatureC - previous[2].temperatureC) / 5.0).coerceIn(-4.0, 4.0), // 18 trend
            solarNormalized * daylightFactor, // 19 solar impact depends on time of day
            cloudNormalized * daylightFactor, // 20 clouds affect daytime heating differently
            latest.cloudLowPct.normalized(center = 50.0, scale = 50.0), // 21-23 cloud layers
            latest.cloudMidPct.normalized(center = 50.0, scale = 50.0),
            latest.cloudHighPct.normalized(center = 50.0, scale = 50.0),
            if (latest.windDirectionDeg == null) 0.0 else sin(directionAngle), // 24-25 circular direction
            if (latest.windDirectionDeg == null) 0.0 else cos(directionAngle),
            latest.vaporPressureDeficitKpa.normalized(center = 1.0, scale = 1.0), // 26
            latest.surfacePressureHpa.normalized(center = 1000.0, scale = 20.0), // 27
            latest.rainMm.normalized(center = 0.2, scale = 1.0), // 28
            latest.showersMm.normalized(center = 0.1, scale = 0.5), // 29
            latest.snowfallCm.normalized(center = 0.0, scale = 1.0), // 30
            latest.visibilityM.normalized(center = 10_000.0, scale = 10_000.0), // 31
            latest.evapotranspirationMm.normalized(center = 0.1, scale = 0.5), // 32
            latest.et0Mm.normalized(center = 0.1, scale = 0.5), // 33
            latest.uvIndex.normalized(center = 3.0, scale = 3.0), // 34
            latest.uvIndexClearSky.normalized(center = 3.0, scale = 3.0), // 35
            latest.wetBulbTemperatureC.normalized(center = BASE_TEMP_C, scale = TEMP_SCALE_C), // 36
            latest.apparentTemperatureC.normalized(center = BASE_TEMP_C, scale = TEMP_SCALE_C), // 37
            latest.solarRadiationInstantWm2.normalized(center = 150.0, scale = 300.0), // 38
            latest.snowDepthM.normalized(center = 0.01, scale = 0.1),
            latest.directRadiationWm2.normalized(center = 100.0, scale = 200.0),
            latest.diffuseRadiationWm2.normalized(center = 50.0, scale = 100.0),
            latest.boundaryLayerHeightM.normalized(center = 1_000.0, scale = 1_500.0),
            latest.windSpeed100mKmh.normalized(center = 20.0, scale = 20.0),
            if (latest.windDirection100mDeg == null) 0.0 else sin(Math.toRadians(latest.windDirection100mDeg)),
            if (latest.windDirection100mDeg == null) 0.0 else cos(Math.toRadians(latest.windDirection100mDeg)),
            latest.soilTemperatureC.normalized(center = BASE_TEMP_C, scale = TEMP_SCALE_C),
            latest.soilMoisture0To7cm.normalized(center = 0.25, scale = 0.25),
            if (weatherCode == 0) 1.0 else 0.0, // 39 clear
            if (weatherCode != null && weatherCode in 1..3) 1.0 else 0.0, // 40 partly/cloudy
            if (weatherCode == 45 || weatherCode == 48) 1.0 else 0.0, // 41 fog
            if (weatherCode != null && weatherCode in 51..57) 1.0 else 0.0, // 42 drizzle/freezing drizzle
            if (weatherCode != null && weatherCode in 61..67) 1.0 else 0.0, // 43 rain/freezing rain
            if (weatherCode != null && (weatherCode in 71..77 || weatherCode == 85 || weatherCode == 86)) 1.0 else 0.0, // 44 snow
            if (weatherCode != null && weatherCode in 80..82) 1.0 else 0.0, // 45 rain showers
            if (weatherCode != null && weatherCode in 95..99) 1.0 else 0.0, // 46 thunderstorm
            ((locationContext.latitude ?: 0.0) / 90.0).coerceIn(-1.0, 1.0), // 47 latitude, south to north
            if (locationContext.longitude == null) 0.0 else sin(Math.toRadians(locationContext.longitude)), // 48 cyclic longitude
            if (locationContext.longitude == null) 0.0 else cos(Math.toRadians(locationContext.longitude)), // 49 cyclic longitude
            locationContext.elevationM.normalized(center = 500.0, scale = 1_000.0), // 50 elevation above mean sea level
            if (locationContext.elevationM == null) 0.0 else 1.0, // 51 elevation availability flag
            ((locationContext.latitude ?: 0.0) / 90.0).coerceIn(-1.0, 1.0) * sin(yearAngle), // 52 hemisphere-season interaction
            ((locationContext.latitude ?: 0.0) / 90.0).coerceIn(-1.0, 1.0) * cos(yearAngle) // 53 hemisphere-season interaction
        )
    }

    /** Null weather features are mean-imputed to zero after centring; missing is not treated as zero C. */
    private fun Double?.normalized(center: Double, scale: Double): Double =
        if (this == null || !isFinite()) 0.0 else ((this - center) / scale).coerceIn(-4.0, 4.0)

    private fun fitWeights(examples: List<Example>, priorWeights: DoubleArray?): DoubleArray {
        val weights = DoubleArray(WEIGHT_COUNT)
        if (priorWeights != null && priorWeights.size == WEIGHT_COUNT) {
            priorWeights.copyInto(weights)
        } else {
            weights[0] = examples.map { it.targetNormalized }.average()
        }
        if (examples.isEmpty()) return weights
        val divisor = examples.size.toDouble()
        repeat(EPOCHS) {
            val gradient = DoubleArray(WEIGHT_COUNT)
            examples.forEach { example ->
                val error = dot(weights, example.features) - example.targetNormalized
                for (featureIndex in weights.indices) {
                    gradient[featureIndex] += (2.0 * error * example.features[featureIndex]) / divisor
                }
            }
            for (index in weights.indices) {
                if (index != 0) gradient[index] += 2.0 * L2 * weights[index]
                if (priorWeights != null && priorWeights.size == WEIGHT_COUNT) {
                    gradient[index] += 2.0 * PRIOR_STRENGTH * (weights[index] - priorWeights[index])
                }
                weights[index] -= LEARNING_RATE * gradient[index]
            }
        }
        return weights
    }

    private fun evaluate(examples: List<Example>, weights: DoubleArray): ErrorStats {
        if (examples.isEmpty()) return ErrorStats(Double.NaN, Double.NaN, 0)
        val signedErrors = examples.map { example ->
            (dot(weights, example.features) * TEMP_SCALE_C + BASE_TEMP_C) - example.actualC
        }
        return ErrorStats(
            maeC = signedErrors.map { abs(it) }.average(),
            biasC = signedErrors.average(),
            count = signedErrors.size
        )
    }

    private fun zone(timezoneId: String): ZoneId =
        runCatching { ZoneId.of(timezoneId) }.getOrElse { ZoneId.of("UTC") }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (index in a.indices) sum += a[index] * b[index]
        return sum
    }
}
