package com.iykyk.assignment

import com.iykyk.assignment.domain.pipeline.CardSlot
import com.iykyk.assignment.domain.pipeline.CollageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageLayoutTest {

    private val canvasWidth = 1080
    private val topBound = 370f
    private val bottomBound = 1790f

    private fun place(count: Int) =
        CollageLayout.place(count, canvasWidth, topBound, bottomBound)

    private fun overlaps(a: CardSlot, b: CardSlot): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test
    fun everyPersonGetsExactlyOneCard() {
        for (count in 1..16) {
            assertEquals("wrong card count for $count people", count, place(count).size)
        }
    }

    @Test
    fun noCardEscapesTheCanvasOrTheContentBand() {
        for (count in 1..16) {
            for (slot in place(count)) {
                assertTrue("card left off canvas at n=$count", slot.left >= 0f)
                assertTrue("card right off canvas at n=$count", slot.right <= canvasWidth)
                assertTrue("card overlaps header at n=$count", slot.top >= topBound - 1f)
                assertTrue("card overlaps footer at n=$count", slot.bottom <= bottomBound + 1f)
            }
        }
    }

    @Test
    fun cardsNeverOverlapEachOther() {
        for (count in 1..16) {
            val slots = place(count)
            for (i in slots.indices) {
                for (j in i + 1 until slots.size) {
                    assertTrue(
                        "cards $i and $j overlap at n=$count",
                        !overlaps(slots[i], slots[j])
                    )
                }
            }
        }
    }

    @Test
    fun cardsStayPortraitAndUsablySized() {
        for (count in 1..16) {
            for (slot in place(count)) {
                assertTrue("card has no width at n=$count", slot.w > 0f)
                assertTrue("card is not portrait at n=$count", slot.h > slot.w)
                assertTrue("card too small to read at n=$count", slot.w >= 120f)
            }
        }
    }

    @Test
    fun layoutIsDeterministic() {
        for (count in 1..16) {
            assertEquals(place(count), place(count))
        }
    }

    @Test
    fun partialLastRowIsCentred() {
        // Five people over three columns leaves two on the second row; they should be
        // centred on the canvas rather than left-aligned under the first two.
        val slots = place(5)
        val lastRow = slots.takeLast(2)
        val midpoint = (lastRow.first().cx + lastRow.last().cx) / 2f
        assertEquals(canvasWidth / 2f, midpoint, 1f)
    }

    @Test
    fun emptyInputProducesNoCards() {
        assertTrue(CollageLayout.place(0, canvasWidth, topBound, bottomBound).isEmpty())
    }
}
