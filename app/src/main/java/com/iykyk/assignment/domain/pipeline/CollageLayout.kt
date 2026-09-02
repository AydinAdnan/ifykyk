package com.iykyk.assignment.domain.pipeline

import kotlin.math.min

/** Position and size of one person's card on the collage. */
data class CardSlot(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rotation: Float
) {
    val left: Float get() = cx - w / 2f
    val top: Float get() = cy - h / 2f
    val right: Float get() = cx + w / 2f
    val bottom: Float get() = cy + h / 2f
}

/**
 * Card placement for the collage, kept free of Android drawing types so the guarantees
 * that matter - one slot per person, every slot on the canvas, no two slots overlapping -
 * can be asserted in JVM unit tests rather than eyeballed on a device.
 */
object CollageLayout {

    /** Deterministic per-card tilt, so re-rendering the same video looks identical. */
    private val TILT_PATTERN = floatArrayOf(-4.5f, 3.8f, -2.6f, 4.2f, -3.4f, 2.9f, -4.0f, 3.2f)

    /** Card width divided by card height. */
    private const val CARD_ASPECT = 0.78f

    /** Below this width a card switches to abbreviated captions. */
    const val COMPACT_WIDTH = 300f

    /**
     * Places [count] cards between [topBound] and [bottomBound].
     *
     * The column count grows with the number of people, so a two-person video gets large,
     * confident cards while a twelve-person video still fits with nobody dropped and
     * nobody drawn twice. The last row is centred when it is not full, so the grid does
     * not end on a ragged gap.
     */
    fun place(
        count: Int,
        canvasWidth: Int,
        topBound: Float,
        bottomBound: Float
    ): List<CardSlot> {
        if (count <= 0) return emptyList()

        val columns = when {
            count == 1 -> 1
            count <= 4 -> 2
            count <= 9 -> 3
            else -> 4
        }
        val rows = (count + columns - 1) / columns

        val sideMargin = if (columns >= 4) 40f else 60f
        val gutter = if (columns >= 4) 14f else 22f
        val available = canvasWidth - sideMargin * 2f
        val verticalSpan = bottomBound - topBound

        val cellW = (available - gutter * (columns - 1)) / columns
        val cellH = (verticalSpan - gutter * (rows - 1)) / rows

        // Keep the portrait proportion, bounded by whichever axis runs out first.
        var cardW = min(cellW, cellH * CARD_ASPECT)
        var cardH = cardW / CARD_ASPECT
        if (cardH > cellH) {
            cardH = cellH
            cardW = cardH * CARD_ASPECT
        }

        val gridH = rows * cardH + gutter * (rows - 1)
        val originY = topBound + (verticalSpan - gridH) / 2f

        return (0 until count).map { i ->
            val col = i % columns
            val row = i / columns

            val itemsInRow = min(columns, count - row * columns)
            val rowW = itemsInRow * cardW + gutter * (itemsInRow - 1)
            val rowX = (canvasWidth - rowW) / 2f

            CardSlot(
                cx = rowX + col * (cardW + gutter) + cardW / 2f,
                cy = originY + row * (cardH + gutter) + cardH / 2f,
                w = cardW,
                h = cardH,
                rotation = TILT_PATTERN[i % TILT_PATTERN.size]
            )
        }
    }
}
