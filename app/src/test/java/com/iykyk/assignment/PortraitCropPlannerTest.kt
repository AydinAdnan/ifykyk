package com.iykyk.assignment

import com.iykyk.assignment.domain.pipeline.Box
import com.iykyk.assignment.domain.pipeline.PortraitCropPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitCropPlannerTest {

    private val frameW = 1080
    private val frameH = 1920

    private fun plan(face: Box, others: List<Box> = emptyList()) =
        PortraitCropPlanner.plan(face, others, frameW, frameH)

    @Test
    fun soloFace_getsGenerousPortraitCrop() {
        val face = Box(440, 500, 640, 760)
        val result = plan(face)

        assertTrue("crop must contain the whole face", result.box.contains(face))
        assertTrue("crop should be clean when nothing else is present", result.isClean)
        assertTrue(
            "crop should be generous, not tight to the face box",
            result.box.width > face.width * 2
        )
        val aspect = result.box.width.toFloat() / result.box.height
        assertEquals("crop should be portrait shaped", PortraitCropPlanner.ASPECT, aspect, 0.05f)
    }

    @Test
    fun neighbourToTheSide_isExcludedEntirely() {
        val face = Box(300, 500, 500, 760)
        val neighbour = Box(620, 520, 820, 780)

        val result = plan(face, listOf(face, neighbour))

        assertTrue(result.box.contains(face))
        assertFalse(
            "no part of the neighbour may remain in the crop",
            result.box.intersects(neighbour)
        )
        assertTrue(result.isClean)
    }

    @Test
    fun neighbourDiagonallyPlaced_isExcluded() {
        // The old midpoint logic only handled neighbours lying entirely to one side,
        // so a diagonal neighbour survived into the crop.
        val face = Box(300, 700, 500, 960)
        val neighbour = Box(560, 400, 760, 660)

        val result = plan(face, listOf(face, neighbour))

        assertTrue(result.box.contains(face))
        assertFalse(result.box.intersects(neighbour))
    }

    @Test
    fun crowdedFrame_excludesEveryOtherFace() {
        val face = Box(460, 800, 640, 1030)
        val others = listOf(
            face,
            Box(120, 780, 300, 1010),
            Box(800, 790, 980, 1020),
            Box(460, 300, 640, 530),
            Box(140, 1300, 320, 1530)
        )

        val result = plan(face, others)

        assertTrue(result.box.contains(face))
        for (other in others.filter { it != face }) {
            assertFalse("crop still contains $other", result.box.intersects(other))
        }
    }

    @Test
    fun adjacentTouchingFaces_reportUncleanRatherThanClippingSubject() {
        val face = Box(480, 800, 640, 1010)
        val neighbour = Box(650, 800, 810, 1010)

        val result = plan(face, listOf(face, neighbour))

        // Whatever happens, the subject must survive intact and the caller must be told
        // the crop is not clean so it can choose a different frame for this person.
        assertTrue(result.box.contains(face))
        if (result.box.intersects(neighbour)) {
            assertFalse(result.isClean)
        }
    }

    @Test
    fun faceNearFrameEdge_cropStaysInsideFrame() {
        val face = Box(20, 40, 200, 280)

        val result = plan(face)

        assertTrue(result.box.left >= 0)
        assertTrue(result.box.top >= 0)
        assertTrue(result.box.right <= frameW)
        assertTrue(result.box.bottom <= frameH)
        assertTrue(result.box.contains(face))
    }

    @Test
    fun cropNeverInvertsOrCollapses() {
        val face = Box(500, 900, 700, 1160)
        val others = listOf(
            face,
            Box(300, 880, 480, 1120),
            Box(720, 880, 900, 1120),
            Box(500, 640, 700, 870),
            Box(500, 1190, 700, 1420)
        )

        val result = plan(face, others)

        assertTrue("width must stay positive", result.box.width > 0)
        assertTrue("height must stay positive", result.box.height > 0)
        assertTrue(result.box.contains(face))
    }
}
