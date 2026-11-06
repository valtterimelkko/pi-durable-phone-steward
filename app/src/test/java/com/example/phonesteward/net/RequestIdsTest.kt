package com.example.phonesteward.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestIdsTest {

    @Test
    fun `new ids are within the 1-128 character rule`() {
        repeat(50) {
            val id = RequestIds.new()
            assertTrue(RequestIds.isValid(id))
            assertTrue(id.startsWith("req-"))
        }
    }

    @Test
    fun `job result ids use the res- form`() {
        assertEquals("res-pj-1", RequestIds.forJobResult("pj-1"))
    }

    @Test
    fun `overlong candidates are clamped deterministically`() {
        val long = "res-" + "a".repeat(200)
        val clamped = RequestIds.clamped(long)
        assertTrue(clamped.length <= 128)
        assertEquals(clamped, RequestIds.clamped(long))
    }
}
