package com.example.phonesteward.screen

/** Reads [ProofActivity]'s "pressed: …" line out of an observed tree. */
object ProofScreen {
    fun pressesIn(texts: List<String>, label: String): Int {
        val line = texts.firstOrNull { it.startsWith("pressed:") } ?: return 0
        return Regex("(?:^|[:;] )" + Regex.escape(label) + "=(\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 0
    }
}
