package com.iykyk.assignment

import com.iykyk.assignment.domain.pipeline.FrameHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameHashTest {

    private fun gradient() = FloatArray(64) { it.toFloat() }

    @Test
    fun identicalFramesHashEqual() {
        assertEquals(FrameHash.ofLumaGrid(gradient()), FrameHash.ofLumaGrid(gradient()))
        assertTrue(
            FrameHash.isDuplicate(
                FrameHash.ofLumaGrid(gradient()),
                FrameHash.ofLumaGrid(gradient())
            )
        )
    }

    @Test
    fun imperceptibleNoiseStillCountsAsDuplicate() {
        // Keyframe snapping and sensor noise produce frames that differ slightly but carry
        // no new information; running detection on them is wasted work.
        val a = gradient()
        val b = FloatArray(64) { a[it] + if (it % 7 == 0) 0.4f else -0.3f }

        assertTrue(FrameHash.isDuplicate(FrameHash.ofLumaGrid(a), FrameHash.ofLumaGrid(b)))
    }

    @Test
    fun differentScenesAreNotDuplicates() {
        val bright = FloatArray(64) { if (it < 32) 250f else 10f }
        val dark = FloatArray(64) { if (it < 32) 10f else 250f }

        val distance = FrameHash.distance(
            FrameHash.ofLumaGrid(bright),
            FrameHash.ofLumaGrid(dark)
        )
        assertEquals("inverted frames should differ in every bit", 64, distance)
        assertFalse(
            FrameHash.isDuplicate(FrameHash.ofLumaGrid(bright), FrameHash.ofLumaGrid(dark))
        )
    }

    @Test
    fun aPersonEnteringTheShotIsNotADuplicate() {
        val empty = FloatArray(64) { 100f }
        // A subject occupying roughly a quarter of the frame.
        val occupied = FloatArray(64) { i ->
            val row = i / 8
            val col = i % 8
            if (row in 2..5 && col in 2..5) 220f else 100f
        }

        assertFalse(
            FrameHash.isDuplicate(FrameHash.ofLumaGrid(empty), FrameHash.ofLumaGrid(occupied))
        )
    }

    @Test
    fun distanceIsSymmetricAndZeroForSelf() {
        val a = FrameHash.ofLumaGrid(gradient())
        val b = FrameHash.ofLumaGrid(FloatArray(64) { 64f - it })

        assertEquals(0, FrameHash.distance(a, a))
        assertEquals(FrameHash.distance(a, b), FrameHash.distance(b, a))
    }

    @Test
    fun rejectsWrongSizedGrid() {
        try {
            FrameHash.ofLumaGrid(FloatArray(16))
            throw AssertionError("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
