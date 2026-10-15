package jp.trisplit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Edge cases of TripleLayout, TaskState and Preset: degenerate input, rotation round trips with
 * odd gaps, and properties that must hold in both orientations.
 */
class LayoutEdgeCaseTest {

    private val portrait = Bounds(0, 89, 1968, 2184)
    private val landscape = Bounds(0, 79, 2184, 1968)

    @Test
    fun `rotating back and forth ten times does not creep the dividers`() {
        // scaleDividers truncates; portrait -> landscape -> portrait loses about 1 px every round trip.
        for (gap in listOf(7, 8, 10, 12)) {
            val start = TripleLayout.dividersOf(TripleLayout.columns(portrait, gap), gap)
            var d = start
            repeat(10) {
                d = TripleLayout.scaleDividers(portrait, landscape, gap, 100, d)
                d = TripleLayout.scaleDividers(landscape, portrait, gap, 100, d)
            }
            d.zip(start).forEach { (now, was) ->
                assertTrue("gap=$gap divider drifted from $was to $now", kotlin.math.abs(now - was) <= 1)
            }
        }
    }

    @Test
    fun `a short getTaskStates array does not throw`() {
        // A Shizuku user service left running from an older build can return fewer ints per task.
        // DividerOverlayService.readStates() calls parseAll outside its runCatching.
        val raw = IntArray(TaskState.SIZE * 2 - 3)
        val states = TaskState.parseAll(raw, 2)
        assertEquals(2, states.size)
        assertTrue(states.none { it.found })
    }

    @Test
    fun `scaling dividers from a zero-width area does not throw`() {
        val empty = Bounds(0, 0, 0, 100)
        TripleLayout.scaleDividers(empty, landscape, 0, 100, listOf(0, 0))
    }

    @Test
    fun `ratios of a zero-width area can be turned back into dividers`() {
        val empty = Bounds(0, 0, 0, 100)
        val ratios = TripleLayout.ratiosOf(empty, listOf(0, 0))
        // (0f / 0) is NaN and NaN.roundToInt() throws IllegalArgumentException.
        TripleLayout.dividersFromRatios(empty, ratios)
    }

    @Test
    fun `decode drops blank components from a truncated line`() {
        // "id <tab> name <tab> a/b <tab>" (cut off mid-write) must not yield a preset with an empty app.
        val presets = Preset.decode("1\tWork\ta/b\t")
        presets.forEach { p -> assertTrue("blank component in $p", p.components.none { it.isBlank() }) }
    }



    @Test
    fun `columns narrower than the screen can hold are never negative when minimums are scaled`() {
        // Area just 2 px wide: scaled minimums are clamped up to 1 px each (3 px) and overflow.
        val tiny = Bounds(0, 0, 2, 100)
        val d = TripleLayout.fitDividers(tiny, 0, listOf(578, 578, 578), listOf(1, 1))
        TripleLayout.columnsFromDividers(tiny, 0, d).forEach { assertTrue("width ${it.width}", it.width >= 0) }
    }

    // ---- behaviour that is correct today (regression pins) ---------------------------------

    @Test
    fun `odd and even gaps round-trip in both orientations`() {
        for (area in listOf(portrait, landscape, Bounds(0, 68, 2184, 1968))) {
            for (gap in 0..11) {
                val cols = TripleLayout.columns(area, gap)
                assertEquals(area.left, cols.first().left)
                assertEquals(area.right, cols.last().right)
                assertEquals(gap, cols[1].left - cols[0].right)
                assertEquals(gap, cols[2].left - cols[1].right)
                val dividers = TripleLayout.dividersOf(cols, gap)
                assertEquals(cols, TripleLayout.columnsFromDividers(area, gap, dividers))
                assertEquals(dividers, TripleLayout.dividersFromBounds(area, gap, 100, cols))
            }
        }
    }

    @Test
    fun `fitDividers and moveDivider always honour per-column minimums`() {
        val rnd = Random(42)
        repeat(3000) {
            val area = listOf(portrait, landscape, Bounds(0, 68, 2184, 1968), Bounds(40, 0, 1040, 500)).random(rnd)
            val gap = rnd.nextInt(0, 12)
            val mins = List(3) { rnd.nextInt(1, 900) }
            val desired = listOf(rnd.nextInt(-200, area.right + 200), rnd.nextInt(-200, area.right + 200))
            val fitted = TripleLayout.fitDividers(area, gap, mins, desired)
            val cols = TripleLayout.columnsFromDividers(area, gap, fitted)
            assertEquals(area.left, cols.first().left)
            assertEquals(area.right, cols.last().right)
            if (TripleLayout.minimumsFit(area, gap, mins)) {
                cols.forEachIndexed { i, c -> assertTrue("fit $area gap=$gap mins=$mins -> $cols", c.width >= mins[i]) }
                val index = rnd.nextInt(0, 2)
                val moved = TripleLayout.moveDivider(area, gap, mins, fitted, index, rnd.nextInt(-300, area.right + 300))
                val after = TripleLayout.columnsFromDividers(area, gap, moved)
                after.forEachIndexed { i, c -> assertTrue("move $area gap=$gap mins=$mins -> $after", c.width >= mins[i]) }
                assertEquals(fitted[1 - index], moved[1 - index])
            } else {
                cols.forEach { assertTrue("scaled $area gap=$gap mins=$mins -> $cols", it.width > 0) }
            }
        }
    }
}
