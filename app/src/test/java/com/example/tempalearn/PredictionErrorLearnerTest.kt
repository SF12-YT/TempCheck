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
}
