package com.example.phonesteward.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Compare-and-set update: if another thread changed the state between the read and the write, the change is made again
 * on the newer state instead of overwriting it. [block] must be pure: it may run more than once.
 */
internal inline fun <T> MutableStateFlow<T>.updateState(block: (T) -> T) {
    while (true) {
        val previous = value
        if (compareAndSet(previous, block(previous))) return
    }
}
