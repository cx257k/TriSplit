package jp.trisplit

import kotlin.math.roundToInt

/** Plain rectangle so the layout math stays testable on the JVM without android.graphics.Rect. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Whether the two rectangles share any area. */
    fun intersects(other: Bounds) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Column layout math. A divider is the x coordinate at the middle of the gap between two columns:
 * column k spans [divider(k-1) + gapRight, divider(k) - gapLeft], where the area edges act as
 * virtual dividers at `left - gapRight` and `right + gapLeft`.
 */
object TripleLayout {
    /**
     * Splits [area] into [count] equal-width vertical columns separated by [gap] pixels.
     * Leftover pixels from integer division go to the leftmost columns so the columns exactly fill [area].
     */
    fun columns(area: Bounds, gap: Int, count: Int = 3): List<Bounds> {
        require(count > 0) { "count must be positive" }
        require(gap >= 0) { "gap must not be negative" }
        val usable = area.width - gap * (count - 1)
        require(usable >= count) { "area too narrow for $count columns" }
        val base = usable / count
        val remainder = usable % count
        var x = area.left
        return List(count) { i ->
            val width = base + if (i < remainder) 1 else 0
            Bounds(x, area.top, x + width, area.bottom).also { x += width + gap }
        }
    }

    /** Divider positions between adjacent [columns]. */
    fun dividersOf(columns: List<Bounds>, gap: Int): List<Int> =
        columns.dropLast(1).map { it.right + gapLeft(gap) }

    /** Inverse of [dividersOf]: the columns that [dividers] cut [area] into. */
    fun columnsFromDividers(area: Bounds, gap: Int, dividers: List<Int>): List<Bounds> {
        val edges = listOf(area.left - gapRight(gap)) + dividers + (area.right + gapLeft(gap))
        return edges.zipWithNext { start, end ->
            Bounds(start + gapRight(gap), area.top, end - gapLeft(gap), area.bottom)
        }
    }

    /**
     * Moves divider [index] to [x], clamped so the column on each side stays at least as wide as its
     * entry in [minWidths] (one per column; some apps cannot be made narrower than their own minimum).
     * The other dividers do not move.
     */
    fun moveDivider(area: Bounds, gap: Int, minWidths: List<Int>, dividers: List<Int>, index: Int, x: Int): List<Int> {
        val prev = if (index == 0) area.left - gapRight(gap) else dividers[index - 1]
        val next = if (index == dividers.lastIndex) area.right + gapLeft(gap) else dividers[index + 1]
        val lower = prev + gap + minWidths[index]
        val upper = next - gap - minWidths[index + 1]
        if (lower > upper) return dividers
        return dividers.toMutableList().also { it[index] = x.coerceIn(lower, upper) }
    }

    /**
     * Follows windows the user resized directly (e.g. One UI's edge drag) instead of via our handles.
     * A column whose width changed drags the divider(s) on the edge(s) that moved; a column that was
     * only moved keeps the dividers, so it snaps back into place. Clamping still applies.
     */
    fun reconcile(
        area: Bounds, gap: Int, minWidths: List<Int>, dividers: List<Int>, expected: List<Bounds>, actual: List<Bounds>,
    ): List<Int> {
        var result = dividers
        for (i in expected.indices) {
            val want = expected[i]
            val got = actual[i]
            if (got.width == want.width) continue
            if (got.right != want.right && i < dividers.size) {
                result = moveDivider(area, gap, minWidths, result, i, got.right + gapLeft(gap))
            }
            if (got.left != want.left && i > 0) {
                result = moveDivider(area, gap, minWidths, result, i - 1, got.left - gapRight(gap))
            }
        }
        return result
    }

    /**
     * Dividers for windows whose bounds we did not choose (e.g. reused tasks that kept old bounds,
     * or the system adjusting a resize): midway between each pair of neighbouring edges, clamped.
     */
    fun dividersFromBounds(area: Bounds, gap: Int, minWidths: List<Int>, actual: List<Bounds>): List<Int> =
        fitDividers(area, gap, minWidths, actual.zipWithNext { a, b -> (a.right + b.left + gapLeft(gap) - gapRight(gap)) / 2 })

    /**
     * Carries the dividers over to a new area (e.g. after rotation), keeping each divider's
     * relative position, then clamps for the new width.
     */
    fun scaleDividers(from: Bounds, to: Bounds, gap: Int, minWidths: List<Int>, dividers: List<Int>): List<Int> {
        if (from.width <= 0) return fitDividers(to, gap, minWidths, dividersOf(columns(to, gap, dividers.size + 1), gap))
        // Rounded, not truncated: rotating back and forth would otherwise creep a pixel each time.
        return fitDividers(to, gap, minWidths, dividers.map { to.left + ((it - from.left).toDouble() * to.width / from.width).roundToInt() })
    }

    /**
     * Moves [desired] dividers as little as possible so every column meets its minimum width. Unlike
     * clamping one divider at a time, a wide column can push its neighbour's divider along: a left
     * to right pass makes room for each column, then a right to left pass keeps the last ones inside
     * the area. If the minimums cannot all fit (e.g. a narrow screen), they are scaled down together
     * until they do, so no column ends up with no width or a negative one.
     */
    fun fitDividers(area: Bounds, gap: Int, requested: List<Int>, desired: List<Int>): List<Int> {
        val room = area.width - gap * (requested.size - 1)
        val minWidths = if (minimumsFit(area, gap, requested)) requested
        else requested.map { maxOf(1, (it.toLong() * room / requested.sum().coerceAtLeast(1)).toInt()) }
        val result = desired.toMutableList()
        var prev = area.left - gapRight(gap)
        for (i in result.indices) {
            result[i] = maxOf(result[i], prev + gap + minWidths[i])
            prev = result[i]
        }
        var next = area.right + gapLeft(gap)
        for (i in result.indices.reversed()) {
            result[i] = minOf(result[i], next - gap - minWidths[i + 1])
            next = result[i]
        }
        return result
    }

    /**
     * The window to place in [slot]: the whole slot, unless the app has a maximum size. Then the
     * window shrinks to it, centred across the slot and aligned to its top.
     */
    fun windowIn(slot: Bounds, limits: WindowLimits): Bounds {
        val width = if (limits.maxWidth in 1 until slot.width) limits.maxWidth else slot.width
        val height = if (limits.maxHeight in 1 until slot.height) limits.maxHeight else slot.height
        val left = slot.left + (slot.width - width) / 2
        return Bounds(left, slot.top, left + width, slot.top + height)
    }

    /** Where the dividers sit across [area], as fractions of its width (0 at the left edge, 1 at the right). */
    fun ratiosOf(area: Bounds, dividers: List<Int>): List<Float> =
        dividers.map { if (area.width > 0) (it - area.left).toFloat() / area.width else 0f }

    /** Inverse of [ratiosOf]: dividers at those fractions of [area]'s width. */
    fun dividersFromRatios(area: Bounds, ratios: List<Float>): List<Int> =
        ratios.map { area.left + (if (it.isFinite()) it * area.width else 0f).roundToInt() }

    /** Whether columns with these minimum widths fit side by side in [area]. */
    fun minimumsFit(area: Bounds, gap: Int, minWidths: List<Int>): Boolean =
        minWidths.sum() + gap * (minWidths.size - 1) <= area.width

    // Same as above with one minimum width for every column.
    fun moveDivider(area: Bounds, gap: Int, minWidth: Int, dividers: List<Int>, index: Int, x: Int) =
        moveDivider(area, gap, List(dividers.size + 1) { minWidth }, dividers, index, x)

    fun reconcile(
        area: Bounds, gap: Int, minWidth: Int, dividers: List<Int>, expected: List<Bounds>, actual: List<Bounds>,
    ) = reconcile(area, gap, List(dividers.size + 1) { minWidth }, dividers, expected, actual)

    fun dividersFromBounds(area: Bounds, gap: Int, minWidth: Int, actual: List<Bounds>) =
        dividersFromBounds(area, gap, List(actual.size) { minWidth }, actual)

    fun scaleDividers(from: Bounds, to: Bounds, gap: Int, minWidth: Int, dividers: List<Int>) =
        scaleDividers(from, to, gap, List(dividers.size + 1) { minWidth }, dividers)

    private fun gapLeft(gap: Int) = gap / 2
    private fun gapRight(gap: Int) = gap - gap / 2
}
