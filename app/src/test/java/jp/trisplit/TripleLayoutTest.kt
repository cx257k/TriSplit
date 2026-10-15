package jp.trisplit

import org.junit.Assert.assertEquals
import org.junit.Test

class TripleLayoutTest {

    @Test
    fun `columns split evenly and fill the area`() {
        val cols = TripleLayout.columns(Bounds(0, 100, 2184, 1900), gap = 0)
        assertEquals(listOf(728, 728, 728), cols.map { it.width })
        assertEquals(0, cols.first().left)
        assertEquals(2184, cols.last().right)
        cols.forEach { assertEquals(100, it.top); assertEquals(1900, it.bottom) }
    }

    @Test
    fun `leftover pixels go to leftmost columns`() {
        val cols = TripleLayout.columns(Bounds(0, 0, 1000, 500), gap = 0)
        assertEquals(listOf(334, 333, 333), cols.map { it.width })
        assertEquals(1000, cols.last().right)
    }

    @Test
    fun `gaps separate columns and respect area offset`() {
        val cols = TripleLayout.columns(Bounds(40, 0, 1040, 500), gap = 10)
        assertEquals(listOf(327, 327, 326), cols.map { it.width })
        assertEquals(cols[0].right + 10, cols[1].left)
        assertEquals(cols[1].right + 10, cols[2].left)
        assertEquals(40, cols.first().left)
        assertEquals(1040, cols.last().right)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects area narrower than gaps`() {
        TripleLayout.columns(Bounds(0, 0, 20, 100), gap = 10)
    }

    @Test
    fun `dividers round-trip back to the same columns`() {
        for (gap in listOf(0, 7, 10)) {
            val area = Bounds(0, 89, 1968, 2184)
            val cols = TripleLayout.columns(area, gap)
            val dividers = TripleLayout.dividersOf(cols, gap)
            assertEquals(2, dividers.size)
            assertEquals(cols, TripleLayout.columnsFromDividers(area, gap, dividers))
        }
    }

    @Test
    fun `moving a divider resizes only its two neighbours`() {
        val area = Bounds(0, 0, 1968, 2000)
        val cols = TripleLayout.columns(area, gap = 0)
        val moved = TripleLayout.moveDivider(area, 0, 100, TripleLayout.dividersOf(cols, 0), index = 0, x = 900)
        val newCols = TripleLayout.columnsFromDividers(area, 0, moved)
        assertEquals(listOf(900, 412, 656), newCols.map { it.width })
        assertEquals(cols[2], newCols[2])
    }

    @Test
    fun `reconcile follows a window resized from its right edge`() {
        val area = Bounds(0, 89, 1968, 2184)
        val cols = TripleLayout.columns(area, 0)
        val dividers = TripleLayout.dividersOf(cols, 0)
        val actual = cols.toMutableList().also { it[0] = it[0].copy(right = 800) }
        val result = TripleLayout.reconcile(area, 0, 100, dividers, cols, actual)
        assertEquals(listOf(800, 1312), result)
    }

    @Test
    fun `reconcile follows a middle window resized from its left edge`() {
        val area = Bounds(0, 89, 1968, 2184)
        val cols = TripleLayout.columns(area, 10)
        val dividers = TripleLayout.dividersOf(cols, 10)
        val actual = cols.toMutableList().also { it[1] = it[1].copy(left = 500) }
        val result = TripleLayout.reconcile(area, 10, 100, dividers, cols, actual)
        assertEquals(500, TripleLayout.columnsFromDividers(area, 10, result)[1].left)
        assertEquals(dividers[1], result[1])
    }

    @Test
    fun `reconcile ignores a window that was only moved`() {
        val area = Bounds(0, 89, 1968, 2184)
        val cols = TripleLayout.columns(area, 0)
        val dividers = TripleLayout.dividersOf(cols, 0)
        val actual = cols.toMutableList().also { it[1] = Bounds(700, 300, 1356, 2000) }
        assertEquals(dividers, TripleLayout.reconcile(area, 0, 100, dividers, cols, actual))
    }

    @Test
    fun `reconcile clamps an over-wide window`() {
        val area = Bounds(0, 0, 1968, 2000)
        val cols = TripleLayout.columns(area, 0)
        val dividers = TripleLayout.dividersOf(cols, 0)
        val actual = cols.toMutableList().also { it[0] = it[0].copy(right = 1900) }
        val result = TripleLayout.reconcile(area, 0, 300, dividers, cols, actual)
        assertEquals(300, TripleLayout.columnsFromDividers(area, 0, result)[1].width)
    }

    @Test
    fun `dividers from tiled bounds match the tiling`() {
        val area = Bounds(0, 89, 1968, 2184)
        for (gap in listOf(0, 7, 10)) {
            val cols = TripleLayout.columns(area, gap)
            assertEquals(TripleLayout.dividersOf(cols, gap), TripleLayout.dividersFromBounds(area, gap, 100, cols))
        }
    }

    @Test
    fun `dividers from overlapping bounds split the overlap`() {
        val area = Bounds(0, 89, 1968, 2184)
        val actual = listOf(Bounds(0, 89, 644, 2184), Bounds(431, 89, 1312, 2184), Bounds(1312, 89, 1968, 2184))
        assertEquals(listOf(537, 1312), TripleLayout.dividersFromBounds(area, 0, 100, actual))
    }

    @Test
    fun `scaling to a rotated area keeps proportions`() {
        val portrait = Bounds(0, 89, 1968, 2184)
        val landscape = Bounds(0, 89, 2184, 1968)
        val dividers = listOf(984, 1476) // 50% / 25% / 25%
        assertEquals(listOf(1092, 1638), TripleLayout.scaleDividers(portrait, landscape, 0, 100, dividers))
    }

    @Test
    fun `scaling equal columns stays tiled and clamped`() {
        val from = Bounds(0, 0, 2184, 1968)
        val to = Bounds(0, 0, 1200, 2000)
        val dividers = listOf(300, 1884)
        val scaled = TripleLayout.scaleDividers(from, to, 10, 300, dividers)
        val cols = TripleLayout.columnsFromDividers(to, 10, scaled)
        assertEquals(0, cols.first().left)
        assertEquals(1200, cols.last().right)
        cols.forEach { assert(it.width >= 300) { "column too narrow: $it" } }
    }

    @Test
    fun `per-column minimum keeps a wide app from being squeezed`() {
        val area = Bounds(0, 79, 2184, 1968)
        val mins = listOf(788, 578, 578)
        val tiled = TripleLayout.columns(area, 0)
        // The app in column 1 refused to go below 788 px and overlaps column 2.
        val actual = tiled.toMutableList().also { it[0] = it[0].copy(right = 788) }
        val dividers = TripleLayout.dividersFromBounds(area, 0, mins, actual)
        val cols = TripleLayout.columnsFromDividers(area, 0, dividers)
        assertEquals(788, cols[0].width)
        assertEquals(cols[0].right, cols[1].left)
        val dragged = TripleLayout.moveDivider(area, 0, mins, dividers, index = 0, x = 300)
        assertEquals(788, dragged[0])
    }

    @Test
    fun `a wide column pushes the next divider in one pass`() {
        // Portrait Fold 7: Clock (min 788 px) in a 656 px column next to two 578 px minimums.
        val area = Bounds(0, 89, 1968, 2184)
        val mins = listOf(788, 578, 578)
        val actual = listOf(Bounds(0, 89, 788, 2184), Bounds(656, 89, 1312, 2184), Bounds(1312, 89, 1968, 2184))
        val cols = TripleLayout.columnsFromDividers(area, 0, TripleLayout.dividersFromBounds(area, 0, mins, actual))
        assertEquals(listOf(788, 578, 602), cols.map { it.width })
    }

    @Test
    fun `divider is clamped to keep minimum widths`() {
        val area = Bounds(0, 0, 1968, 2000)
        val dividers = TripleLayout.dividersOf(TripleLayout.columns(area, 10), 10)
        val farLeft = TripleLayout.moveDivider(area, 10, 300, dividers, index = 0, x = -50)
        assertEquals(300, TripleLayout.columnsFromDividers(area, 10, farLeft)[0].width)
        val farRight = TripleLayout.moveDivider(area, 10, 300, dividers, index = 1, x = 5000)
        assertEquals(300, TripleLayout.columnsFromDividers(area, 10, farRight)[2].width)
        val pastNeighbour = TripleLayout.moveDivider(area, 10, 300, dividers, index = 0, x = dividers[1])
        assertEquals(300, TripleLayout.columnsFromDividers(area, 10, pastNeighbour)[1].width)
    }

    @Test
    fun `minimums too wide for the screen shrink instead of going negative`() {
        val area = Bounds(0, 0, 1080, 2000)
        val dividers = TripleLayout.fitDividers(area, 8, listOf(578, 578, 578), listOf(360, 720))
        val widths = TripleLayout.columnsFromDividers(area, 8, dividers).map { it.width }
        widths.forEach { assert(it > 0) { "column width $it" } }
        assertEquals(1080 - 2 * 8, widths.sum())
    }

    @Test
    fun `remembered widths carry over between orientations`() {
        val portrait = Bounds(0, 89, 1968, 2184)
        val landscape = Bounds(0, 79, 2184, 1968)
        val dividers = listOf(500, 1300)
        val ratios = TripleLayout.ratiosOf(portrait, dividers)
        assertEquals(dividers, TripleLayout.dividersFromRatios(portrait, ratios))
        assertEquals(listOf(555, 1443), TripleLayout.dividersFromRatios(landscape, ratios))
    }
}
