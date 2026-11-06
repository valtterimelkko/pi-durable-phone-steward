package com.example.phonesteward.ui

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * N39: the view model's state is changed from the main thread and from IO threads at once (a refresh lands while the
 * owner taps a setting). A read-then-write update loses one of two overlapping changes, so a tap on the Chat detail
 * control right after pairing could be undone by a refresh that started before it.
 */
class StateUpdateTest {

    private data class S(val counter: Int = 0, val other: Int = 0)

    @Test
    fun `overlapping updates from many threads are all kept`() {
        val flow = MutableStateFlow(S())
        val threads = 8
        val each = 20_000
        val go = CountDownLatch(1)
        val workers = (0 until threads).map { n ->
            Thread {
                go.await()
                repeat(each) { if (n % 2 == 0) flow.updateState { it.copy(counter = it.counter + 1) } else flow.updateState { it.copy(other = it.other + 1) } }
            }.also { it.start() }
        }
        go.countDown()
        workers.forEach { it.join() }
        assertEquals(threads / 2 * each, flow.value.counter)
        assertEquals(threads / 2 * each, flow.value.other)
    }
}
