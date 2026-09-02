package com.iykyk.assignment

import com.iykyk.assignment.domain.pipeline.ThresholdCalibrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdCalibratorTest {

    private fun calibrate(genuine: List<Float>, impostor: List<Float>) =
        ThresholdCalibrator.calibrate(genuine, impostor)

    @Test
    fun wellSeparatedDistributionsGiveAThresholdBetweenThem() {
        val impostor = listOf(0.05f, 0.11f, 0.18f, 0.09f, 0.14f, 0.20f)
        val genuine = listOf(0.71f, 0.80f, 0.66f, 0.75f, 0.69f, 0.83f)

        val t = calibrate(genuine, impostor)
        assertTrue("threshold must clear the impostor tail, was $t", t > 0.20f)
        assertTrue("threshold must admit the weakest genuine pair, was $t", t < 0.66f)
    }

    @Test
    fun theSamePersonInFourTrackletsStaysOnePerson() {
        // The reported failure: one person split four ways. Their tracklet pairs sit
        // around 0.5 - well above this footage's impostor tail, but below the 0.55
        // constant that was hardcoded before calibration.
        val impostor = listOf(0.10f, 0.16f, 0.21f, 0.13f, 0.19f, 0.24f)
        val genuine = listOf(0.52f, 0.58f, 0.49f, 0.61f, 0.55f, 0.50f)

        val t = calibrate(genuine, impostor)
        assertTrue(
            "tracklets of one person at 0.49 must merge, threshold was $t",
            t < 0.49f
        )
    }

    @Test
    fun noisyEmbeddingsStillSeparateDistinctPeople() {
        // Quantised embeddings push impostor pairs higher. The threshold has to follow
        // them up, or two people merge into one.
        val impostor = listOf(0.38f, 0.44f, 0.41f, 0.47f, 0.36f, 0.43f)
        val genuine = listOf(0.78f, 0.83f, 0.75f, 0.88f, 0.80f, 0.79f)

        val t = calibrate(genuine, impostor)
        assertTrue("threshold must sit above the impostor tail, was $t", t > 0.47f)
    }

    @Test
    fun overlappingDistributionsSitAboveTheImpostorTail() {
        // When the embeddings cannot separate these faces at any threshold, keeping
        // distinct people apart is the error a viewer is least likely to forgive.
        val impostor = listOf(0.50f, 0.55f, 0.60f, 0.52f, 0.58f)
        val genuine = listOf(0.45f, 0.50f, 0.62f, 0.48f, 0.55f)

        val t = calibrate(genuine, impostor)
        assertTrue("threshold should not fall below the impostor tail, was $t", t >= 0.55f)
    }

    @Test
    fun tooLittleEvidenceFallsBackToTheSuppliedDefault() {
        assertEquals(
            0.5f,
            ThresholdCalibrator.calibrate(listOf(0.8f), listOf(0.1f), fallback = 0.5f),
            1e-6f
        )
        assertEquals(
            ThresholdCalibrator.DEFAULT_THRESHOLD,
            calibrate(emptyList(), emptyList()),
            1e-6f
        )
    }

    @Test
    fun impostorEvidenceAloneStillRaisesTheThreshold() {
        val impostor = listOf(0.40f, 0.45f, 0.42f, 0.48f, 0.44f, 0.46f)

        val t = calibrate(emptyList(), impostor)
        assertTrue("must clear the observed impostors, was $t", t > 0.48f)
    }

    @Test
    fun resultIsAlwaysWithinSaneBounds() {
        val extremes = listOf(
            emptyList<Float>() to listOf(0.99f, 0.98f, 0.97f, 0.99f, 0.98f, 0.99f),
            listOf(0.01f, 0.02f, 0.03f, 0.01f, 0.02f, 0.01f) to emptyList(),
            listOf(-0.9f, -0.8f, -0.7f, -0.9f, -0.8f, -0.7f) to
                listOf(0.99f, 0.99f, 0.99f, 0.99f, 0.99f, 0.99f)
        )

        for ((genuine, impostor) in extremes) {
            val t = calibrate(genuine, impostor)
            assertTrue(
                "threshold $t escaped its bounds",
                t >= ThresholdCalibrator.MIN_THRESHOLD && t <= ThresholdCalibrator.MAX_THRESHOLD
            )
        }
    }

    @Test
    fun percentileTracksTheRequestedTail() {
        val values = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        assertEquals(0.1f, ThresholdCalibrator.percentile(values, 0f), 1e-6f)
        assertEquals(0.3f, ThresholdCalibrator.percentile(values, 0.5f), 1e-6f)
        assertEquals(0.5f, ThresholdCalibrator.percentile(values, 1f), 1e-6f)
    }
}
