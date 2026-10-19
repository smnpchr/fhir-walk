package dev.fhirwalk

import dev.fhirwalk.repl.fitColumns
import kotlin.test.Test
import kotlin.test.assertEquals

/** How `:view` shares the window between its columns. */
class ColumnFitTest {

    @Test
    fun `a table that fits keeps every column whole`() {
        assertEquals(listOf(8, 6, 19), fitColumns(listOf(8, 6, 19), room = 40, minimum = 12))
    }

    @Test
    fun `narrow columns hand their spare width to a wide one`() {
        // An even split would give each 13, shortening the last although 18 are free.
        assertEquals(listOf(8, 6, 26), fitColumns(listOf(8, 6, 30), room = 40, minimum = 12))
    }

    @Test
    fun `only the widest columns are shortened, sharing what is left`() {
        assertEquals(listOf(10, 16, 15), fitColumns(listOf(10, 30, 50), room = 41, minimum = 12))
    }

    @Test
    fun `a shortened column keeps the minimum even past the room`() {
        assertEquals(listOf(12, 12), fitColumns(listOf(30, 40), room = 10, minimum = 12))
    }

    @Test
    fun `a window with no room left still draws every column at the minimum`() {
        assertEquals(listOf(12, 12), fitColumns(listOf(20, 30), room = -5, minimum = 12))
    }

    @Test
    fun `a column under the minimum is not widened to it`() {
        // 11 does not fit a share of 10, but the minimum must not pad it to 12.
        assertEquals(listOf(11, 12), fitColumns(listOf(11, 100), room = 20, minimum = 12))
    }
}
