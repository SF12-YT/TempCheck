package com.example.tempalearn

import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp

/**
 * A completed one-hour-ahead prediction, retained until its matching hourly reading arrives.
 * Errors use actual - predicted, so a positive error means the model was too cool.
 */
data class PredictionErrorSample(
    val targetTimestampSec: Long,
    val predictedTempC: Double,
    val actualTempC: Double,
    val localHour: Int,
    val temperatureTrendC: Double,
    val cloudCoverPct: Double?,
    val solarRadiationWm2: Double?,
    val weatherCode: Int?
)

data class PredictionCorrection(
    val correctionC: Double,
    val verifiedPredictions: Int,
    val globalBiasC: Double,
    val contextAdjustmentC: Double
)

data class FeedbackAdjustedPrediction(
    val targetTimestampSec: Long,
    val temperatureC: Double,
    val correctionC: Double,
    val verifiedPredictions: Int
)

object PredictionVerificationMethod {
    const val LIVE = "LIVE"
    const val WALK_FORWARD_REPLAY = "WALK_FORWARD_REPLAY"
}

data class WalkForwardPrediction(
    val targetTimestampSec: Long,
    val predictionMadeAtSec: Long,
    val predictedTempC: Double,
    val localHour: Int,
    val temperatureTrendC: Double,
    val cloudCoverPct: Double?,
    val solarRadiationWm2: Double?,
    val weatherCode: Int?,
    val correctionC: Double
)

/**
 * Replays one strict one-hour step using only observations that precede the target.
 * The caller invokes this before inserting the target observation, so the model cannot
 * see the answer it is being checked against. Feedback samples contain only earlier
 * targets that have already been reconciled to actual readings.
 */
fun predictWalkForwardTarget(
    priorReadings: List<HourlyTemperature>,
    targetTimestampSec: Long,
    model: LearnedWeatherModel,
    timezoneId: String,
    locationContext: ModelLocationContext = ModelLocationContext(),
    feedbackSamples: List<PredictionErrorSample> = emptyList()
): WalkForwardPrediction? {
    val recent = priorReadings.takeLast(3)
    if (recent.size != 3 || recent.zipWithNext().any { (a, b) ->
            b.timestampSec - a.timestampSec != 3_600L
        }
    ) return null

    val base = WeatherLearner.predictNext(recent, model, timezoneId, locationContext)
        ?: return null
    if (base.first != targetTimestampSec) return null

    val latest = recent.last()
    val trend = latest.temperatureC - recent[1].temperatureC
    val correction = PredictionErrorLearner.correction(
        samples = feedbackSamples,
        targetTimestampSec = targetTimestampSec,
        timezoneId = timezoneId,
        temperatureTrendC = trend,
        cloudCoverPct = latest.cloudCoverPct,
        solarRadiationWm2 = latest.solarRadiationWm2,
        weatherCode = latest.weatherCode
    )
    val zone = runCatching { ZoneId.of(timezoneId) }.getOrElse { ZoneId.of("UTC") }
    val localHour = Instant.ofEpochSecond(targetTimestampSec).atZone(zone).hour

    return WalkForwardPrediction(
        targetTimestampSec = targetTimestampSec,
        predictionMadeAtSec = latest.timestampSec,
        predictedTempC = (base.second + correction.correctionC).coerceIn(-90.0, 70.0),
        localHour = localHour,
        temperatureTrendC = trend,
        cloudCoverPct = latest.cloudCoverPct,
        solarRadiationWm2 = latest.solarRadiationWm2,
        weatherCode = latest.weatherCode,
        correctionC = correction.correctionC
    )
}

/**
 * Conservative online calibration built from predictions that have subsequently been checked
 * against a stored hourly reading. This is deliberately separate from the 62-weight historical
 * regression model: sparse feedback must not destabilise its fitted coefficients.
 */
object PredictionErrorLearner {
    fun correction(
        samples: List<PredictionErrorSample>,
        targetTimestampSec: Long,
        timezoneId: String,
        temperatureTrendC: Double,
        cloudCoverPct: Double?,
        solarRadiationWm2: Double?,
        weatherCode: Int?
    ): PredictionCorrection {
        val recent = samples
            .asSequence()
            .filter {
                it.predictedTempC.isFinite() && it.actualTempC.isFinite() &&
                    it.temperatureTrendC.isFinite()
            }
            .sortedByDescending { it.targetTimestampSec }
            .toList()

        if (recent.isEmpty()) {
            return PredictionCorrection(0.0, 0, 0.0, 0.0)
        }

        // Start learning from the first verified forecast, but shrink early corrections
        // toward zero so one unusual hour cannot swing the next prediction too far.
        // Every verified sample is retained and considered; recency weighting limits stale influence.
        val rawGlobalBias = weightedMeanError(recent) ?: 0.0
        val confidence = recent.size.toDouble() / (recent.size + 8.0)
        val globalBias = rawGlobalBias * confidence
        val targetHour = runCatching {
            Instant.ofEpochSecond(targetTimestampSec).atZone(ZoneId.of(timezoneId)).hour
        }.getOrDefault(Instant.ofEpochSecond(targetTimestampSec).atZone(ZoneId.of("UTC")).hour)

        // Context effects are shrunk toward the global bias and kept deliberately modest.
        // They are learned independently so a handful of unusual hours cannot dominate.
        // Compare each context group with the unshrunk baseline. Comparing against the
        // confidence-shrunk value would make identical groups reintroduce the removed bias.
        val hourAdjustment = groupAdjustment(recent.filter { it.localHour == targetHour }, rawGlobalBias, 0.40)
        val trendKey = trendBucket(temperatureTrendC)
        val trendAdjustment = groupAdjustment(
            recent.filter { trendBucket(it.temperatureTrendC) == trendKey },
            rawGlobalBias,
            0.35
        )
        val cloudKey = cloudBucket(cloudCoverPct)
        val cloudAdjustment = if (cloudKey == null) 0.0 else groupAdjustment(
            recent.filter { cloudBucket(it.cloudCoverPct) == cloudKey },
            rawGlobalBias,
            0.25
        )
        val solarKey = solarBucket(solarRadiationWm2)
        val solarAdjustment = if (solarKey == null) 0.0 else groupAdjustment(
            recent.filter { solarBucket(it.solarRadiationWm2) == solarKey },
            rawGlobalBias,
            0.25
        )
        val weatherKey = weatherBucket(weatherCode)
        val weatherAdjustment = if (weatherKey == null) 0.0 else groupAdjustment(
            recent.filter { weatherBucket(it.weatherCode) == weatherKey },
            rawGlobalBias,
            0.15
        )

        val stableGlobal = globalBias.coerceIn(-2.5, 2.5)
        val context = (
            hourAdjustment + trendAdjustment + cloudAdjustment +
                solarAdjustment + weatherAdjustment
            ).coerceIn(-1.25, 1.25)
        return PredictionCorrection(
            correctionC = (stableGlobal + context).coerceIn(-3.5, 3.5),
            verifiedPredictions = recent.size,
            globalBiasC = stableGlobal,
            contextAdjustmentC = context
        )
    }

    private fun groupAdjustment(
        group: List<PredictionErrorSample>,
        globalBias: Double,
        strength: Double
    ): Double {
        if (group.size < 4) return 0.0
        val groupBias = weightedMeanError(group) ?: return 0.0
        val shrinkage = group.size.toDouble() / (group.size + 12.0)
        return ((groupBias - globalBias) * shrinkage * strength).coerceIn(-1.0, 1.0)
    }

    private fun weightedMeanError(samples: List<PredictionErrorSample>): Double? {
        if (samples.isEmpty()) return null
        var weightedError = 0.0
        var totalWeight = 0.0
        samples.forEachIndexed { index, sample ->
            // The newest verified forecasts receive more influence; older seasons remain useful.
            val weight = exp(-index / 70.0)
            val error = (sample.actualTempC - sample.predictedTempC).coerceIn(-8.0, 8.0)
            weightedError += error * weight
            totalWeight += weight
        }
        return if (totalWeight > 0.0) weightedError / totalWeight else null
    }

    private fun trendBucket(value: Double): Int = when {
        value > 0.35 -> 1
        value < -0.35 -> -1
        else -> 0
    }

    private fun cloudBucket(value: Double?): Int? = value?.takeIf { it.isFinite() }?.let {
        when {
            it < 35.0 -> 0
            it < 70.0 -> 1
            else -> 2
        }
    }

    private fun solarBucket(value: Double?): Int? = value?.takeIf { it.isFinite() }?.let {
        when {
            it < 10.0 -> 0
            it < 150.0 -> 1
            else -> 2
        }
    }

    private fun weatherBucket(code: Int?): Int? = code?.let {
        when (it) {
            45, 48 -> 2 // fog
            in 51..99 -> 1 // precipitation, snow or thunderstorms
            else -> 0 // mainly dry conditions
        }
    }
}

/**
 * Generates the exact value shown by the app, learns only from previously verified forecasts,
 * and stores this forecast only while its target hour is still current/future.
 */
fun predictAndStoreNextHour(
    database: TemperatureDatabase,
    locationKey: String,
    readings: List<HourlyTemperature>,
    model: LearnedWeatherModel,
    timezoneId: String,
    locationContext: ModelLocationContext = ModelLocationContext(),
    nowSec: Long = Instant.now().epochSecond
): FeedbackAdjustedPrediction? {
    // Reconcile on every prediction refresh, not only after a network sync. A matching
    // reading may already be stored (for example after a retry or another import path).
    database.reconcilePendingPredictions(locationKey, nowSec)

    val base = WeatherLearner.predictNext(readings, model, timezoneId, locationContext) ?: return null
    val latest = readings.takeLast(3)
    if (latest.size != 3) return null
    val mostRecent = latest.last()
    val trend = mostRecent.temperatureC - latest[1].temperatureC
    val correction = PredictionErrorLearner.correction(
        samples = database.getVerifiedPredictionErrors(locationKey),
        targetTimestampSec = base.first,
        timezoneId = timezoneId,
        temperatureTrendC = trend,
        cloudCoverPct = mostRecent.cloudCoverPct,
        solarRadiationWm2 = mostRecent.solarRadiationWm2,
        weatherCode = mostRecent.weatherCode
    )
    val adjustedTemperature = (base.second + correction.correctionC).coerceIn(-90.0, 70.0)

    // Only genuinely prospective predictions may enter the feedback dataset. Never label
    // an estimate produced after its target hour as a forecast.
    if (base.first > nowSec) {
        val localHour = runCatching {
            Instant.ofEpochSecond(base.first).atZone(ZoneId.of(timezoneId)).hour
        }.getOrDefault(Instant.ofEpochSecond(base.first).atZone(ZoneId.of("UTC")).hour)
        database.savePendingPrediction(
            locationKey = locationKey,
            targetTimestampSec = base.first,
            predictedTempC = adjustedTemperature,
            predictedAtSec = nowSec,
            localHour = localHour,
            temperatureTrendC = trend,
            cloudCoverPct = mostRecent.cloudCoverPct,
            solarRadiationWm2 = mostRecent.solarRadiationWm2,
            weatherCode = mostRecent.weatherCode
        )
    }

    return FeedbackAdjustedPrediction(
        targetTimestampSec = base.first,
        temperatureC = adjustedTemperature,
        correctionC = correction.correctionC,
        verifiedPredictions = correction.verifiedPredictions
    )
}
