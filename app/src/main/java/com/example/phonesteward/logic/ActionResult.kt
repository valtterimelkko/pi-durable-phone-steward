package com.example.phonesteward.logic

/**
 * N16: the outcome of an owner action, shown where the action happened (a
 * result card on Proposals), not only on Status. Pure data; the screens pick
 * the colour from [kind].
 */
data class ActionResult(val text: String, val kind: Kind) {
    enum class Kind { SUCCESS, INFO, ERROR }
}
