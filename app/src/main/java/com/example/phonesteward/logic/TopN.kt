package com.example.phonesteward.logic

/**
 * Stable top-N selection: items with equal scores keep their input order,
 * so results are deterministic for a given source snapshot.
 */
object TopN {

    fun <T> topN(items: List<T>, n: Int, score: (T) -> Long): List<T> {
        if (n <= 0) return emptyList()
        return items.sortedByDescending(score).take(n)
    }
}
