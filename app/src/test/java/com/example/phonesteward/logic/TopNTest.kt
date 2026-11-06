package com.example.phonesteward.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class TopNTest {

    private fun app(name: String, total: Long) = AppSizeRow(name, total, 0, 0)

    @Test
    fun `sorts by score descending`() {
        val items = listOf(app("small", 10), app("big", 90), app("middle", 50))
        val result = TopN.topN(items, 2) { it.totalBytes }
        assertEquals(listOf("big", "middle"), result.map { it.packageName })
    }

    @Test
    fun `ties keep their input order`() {
        val items = listOf(
            app("first", 100),
            app("second", 100),
            app("third", 100),
            app("below", 1),
        )
        val result = TopN.topN(items, 4) { it.totalBytes }
        assertEquals(listOf("first", "second", "third", "below"), result.map { it.packageName })
    }

    @Test
    fun `takes only the requested number`() {
        val items = (1L..10L).map { app("app$it", it) }
        val result = TopN.topN(items, 3) { it.totalBytes }
        assertEquals(3, result.size)
        assertEquals(listOf("app10", "app9", "app8"), result.map { it.packageName })
    }

    @Test
    fun `asking for more than exists returns everything`() {
        val items = listOf(app("a", 1))
        assertEquals(1, TopN.topN(items, 15) { it.totalBytes }.size)
    }

    @Test
    fun `empty input gives empty output`() {
        assertEquals(0, TopN.topN(emptyList<AppSizeRow>(), 15) { it.totalBytes }.size)
    }

    @Test
    fun `zero or negative n gives empty output`() {
        val items = listOf(app("a", 1))
        assertEquals(0, TopN.topN(items, 0) { it.totalBytes }.size)
    }
}
