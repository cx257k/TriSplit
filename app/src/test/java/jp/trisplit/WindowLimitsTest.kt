package jp.trisplit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLimitsTest {

    private val slot = Bounds(656, 89, 1312, 2184)

    @Test
    fun `window fills its slot without maximums`() {
        assertEquals(slot, TripleLayout.windowIn(slot, WindowLimits(minWidth = 578)))
        assertEquals(slot, TripleLayout.windowIn(slot, WindowLimits(578, maxWidth = -1, maxHeight = -1)))
    }

    @Test
    fun `maximum width centres a narrower window in the slot`() {
        val window = TripleLayout.windowIn(slot, WindowLimits(578, maxWidth = 600))
        assertEquals(Bounds(684, 89, 1284, 2184), window)
    }

    @Test
    fun `maximum height keeps the window at the top`() {
        val window = TripleLayout.windowIn(slot, WindowLimits(578, maxHeight = 1500))
        assertEquals(Bounds(656, 89, 1312, 1589), window)
    }

    @Test
    fun `maximum larger than the slot changes nothing`() {
        assertEquals(slot, TripleLayout.windowIn(slot, WindowLimits(578, maxWidth = 5000, maxHeight = 5000)))
    }

    @Test
    fun `minimums that do not fit are detected`() {
        val portrait = Bounds(0, 89, 1968, 2184)
        assertTrue(TripleLayout.minimumsFit(portrait, 0, listOf(788, 578, 578)))
        assertFalse(TripleLayout.minimumsFit(portrait, 0, listOf(788, 788, 578)))
        assertTrue(TripleLayout.minimumsFit(Bounds(0, 79, 2184, 1968), 0, listOf(788, 788, 578)))
    }

    @Test
    fun `task states parse and give column limits`() {
        val raw = intArrayOf(
            1, 1, 5, 0, 89, 788, 2184, 788, 220, -1, -1,
            1, 0, 1, 0, 0, 1968, 2184, -1, 220, 900, 1800,
        )
        val (clock, other) = TaskState.parseAll(raw, 2)
        assertTrue(clock.freeform)
        assertEquals(Bounds(0, 89, 788, 2184), clock.bounds)
        assertEquals(WindowLimits(788, -1, -1), clock.limits(density = 2.625f, floorPx = 578))
        assertFalse(other.visible)
        assertFalse(other.freeform)
        assertEquals(WindowLimits(578, 900, 1800), other.limits(density = 2.625f, floorPx = 578))
    }
}
