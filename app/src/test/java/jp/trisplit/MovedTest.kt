package jp.trisplit

import org.junit.Assert.assertEquals
import org.junit.Test

class MovedTest {
    @Test
    fun `moves an element down`() {
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moved(0, 2))
    }

    @Test
    fun `moves an element up`() {
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, 0))
    }

    @Test
    fun `moving to the same place changes nothing`() {
        assertEquals(listOf("a", "b", "c"), listOf("a", "b", "c").moved(1, 1))
    }
}
