package com.example.tempalearn

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionErrorLearnerTest {
    private val targetTimestamp = ZonedDateTime.of(
        2026, 11, 1, 14, 0, 0, 0, ZoneId.of("Australia/Sydney")
    ).toEpochSecond()

    private fun samples(count: Int, errorC: Double): List<PredictionErrorSample> =
        List(count) { index ->
            PredictionErrorSample(
                targetTimestampSec = targetTimestamp - (count - index) * 3_600L,
                predictedTempC = 20.0,
                actualTempC = 20.0 + errorC,
                localHour = 14,
                temperatureTrendC = 0.8,
                cloudCoverPct = 20.0,
                solarRadiationWm2 = 200.0,
                weatherCode = 0
            )
        }

    private fun correction(samples: List<PredictionErrorSample>, trend: Double = 0.8) =
        PredictionErrorLearner.correction(
            samples = samples,
            targetTimestampSec = targetTimestamp,
            timezoneId = "Australia/Sydney",
            temperatureTrendC = trend,
            cloudCoverPct = 20.0,
            solarRadiationWm2 = 200.0,
            weatherCode = 0
        )

    @Test
    fun startsLearningFromTheFirstMatchedForecastWithConservativeShrinkage() {
        val result = correction(samples(1, 4.0))
        assertTrue("Expected a positive correction, got ${result.correctionC}", result.correctionC > 0.0)
        assertTrue("Early feedback should be shrunk, got ${result.correctionC}", result.correctionC < 4.0)
        assertEquals(1, result.verifiedPredictions)
    }

    @Test
    fun continuesCountingAndUsingForecastsBeyondTheOldEightSampleThreshold() {
        val result = correction(samples(250, 2.0))
        assertEquals(250, result.verifiedPredictions)
        assertTrue("Expected a positive correction, got ${result.correctionC}", result.correctionC > 0.0)
        assertTrue("Correction should remain conservative, got ${result.correctionC}", result.correctionC < 2.0)
    }

    @Test
    fun correctsRepeatedCoolPredictionsInTheWarmerDirection() {
        val result = correction(samples(8, 2.0))
        assertTrue("Expected a positive correction, got ${result.correctionC}", result.correctionC > 0.0)
        assertEquals(1.0, result.correctionC, 0.0001)
    }

    @Test
    fun correctsRepeatedWarmPredictionsInTheCoolerDirection() {
        val result = correction(samples(8, -2.0))
        assertTrue("Expected a negative correction, got ${result.correctionC}", result.correctionC < 0.0)
        assertEquals(-1.0, result.correctionC, 0.0001)
    }

    @Test
    fun neverAllowsAnUnboundedCorrectionFromLargeErrors() {
        val result = correction(samples(20, 15.0))
        assertTrue(result.correctionC <= 3.5)
        assertTrue(result.correctionC >= -3.5)
    }

    @Test
    fun contextualCorrectionIsOnlyAppliedWhenTheGroupHasEnoughExamples() {
        val mixed = samples(12, 0.0).mapIndexed { index, sample ->
            if (index >= 6) sample.copy(actualTempC = 23.0)
            else sample.copy(actualTempC = 17.0, temperatureTrendC = -0.8)
        }
        val result = correction(mixed, trend = 0.8)
        assertTrue("Expected the warming trend group to receive a positive adjustment", result.contextAdjustmentC > 0.0)
    }

    private fun walkForwardModel() = LearnedWeatherModel(
        weights = List(WeatherLearner.WEIGHT_COUNT) { 0.0 },
        trainingExamples = 100,
        validationMaeC = 1.0,
        validationBiasC = 0.0,
        validationExamples = 20,
        trainedAtSec = targetTimestamp,
        trainingStartSec = targetTimestamp - 100_000L,
        trainingEndSec = targetTimestamp - 3_600L
    )

    private fun threePriorReadings(): List<HourlyTemperature> = listOf(
        HourlyTemperature(targetTimestamp - 3 * 3_600L, 18.0),
        HourlyTemperature(targetTimestamp - 2 * 3_600L, 19.0),
        HourlyTemperature(targetTimestamp - 3_600L, 20.0)
    )

    @Test
    fun nextLocalHourTargetRemainsFutureWhenLatestObservationIsDelayed() {
        val zone = ZoneId.of("Australia/Sydney")
        val now = ZonedDateTime.of(2026, 11, 2, 17, 38, 0, 0, zone).toEpochSecond()
        val expected = ZonedDateTime.of(2026, 11, 2, 18, 0, 0, 0, zone).toEpochSecond()
        assertEquals(expected, nextLocalHourTimestamp(now, zone.id))
    }

    @Test
    fun livePredictionCanTargetNextClockHourWithTwoHourOldObservation() {
        val target = targetTimestamp + 3_600L
        val result = WeatherLearner.predictAtTimestamp(
            readings = threePriorReadings(),
            model = walkForwardModel(),
            timezoneId = "Australia/Sydney",
            targetTimestampSec = target
        )
        assertTrue("Expected a live estimate for a future target", result != null)
        assertEquals(target, result!!.first)
        assertEquals(20.0, result.second, 0.0001)
    }

    @Test
    fun livePredictionRejectsTargetsMoreThanThreeHoursAfterLatestObservation() {
        val result = WeatherLearner.predictAtTimestamp(
            readings = threePriorReadings(),
            model = walkForwardModel(),
            timezoneId = "Australia/Sydney",
            targetTimestampSec = targetTimestamp + 3 * 3_600L
        )
        assertTrue("Excessively stale inputs must not create a live forecast", result == null)
    }

    @Test
    fun walkForwardPredictionUsesOnlyTheThreeHoursImmediatelyBeforeItsTarget() {
        val result = predictWalkForwardTarget(
            priorReadings = threePriorReadings(),
            targetTimestampSec = targetTimestamp,
            model = walkForwardModel(),
            timezoneId = "Australia/Sydney"
        )

        assertTrue("Expected a walk-forward estimate", result != null)
        assertEquals(targetTimestamp, result!!.targetTimestampSec)
        assertEquals(targetTimestamp - 3_600L, result.predictionMadeAtSec)
        assertEquals(20.0, result.predictedTempC, 0.0001)
        assertEquals(14, result.localHour)
    }

    @Test
    fun walkForwardPredictionRejectsNonConsecutiveOrMismatchedTargets() {
        val gap = threePriorReadings().mapIndexed { index, reading ->
            if (index == 1) reading.copy(timestampSec = reading.timestampSec - 3_600L) else reading
        }
        assertTrue(
            predictWalkForwardTarget(
                priorReadings = gap,
                targetTimestampSec = targetTimestamp,
                model = walkForwardModel(),
                timezoneId = "Australia/Sydney"
            ) == null
        )
        assertTrue(
            predictWalkForwardTarget(
                priorReadings = threePriorReadings(),
                targetTimestampSec = targetTimestamp + 3_600L,
                model = walkForwardModel(),
                timezoneId = "Australia/Sydney"
            ) == null
        )
    }

}
