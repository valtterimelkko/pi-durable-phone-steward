package com.example.phonesteward.logic.shizuku

/** What the app needs from Shizuku; the real one is `AndroidShizuku`, tests use fakes. */
interface ShizukuAccess {
    /** The four-way state, read now. */
    fun state(): ShizukuState

    /** The shell rights, usable only while [state] is [ShizukuState.READY]. */
    fun shell(): ShellRunner

    /** Asks Shizuku (its own dialog) to let the steward use it. No-op unless the service is running. */
    fun requestPermission()

    /** [onChange] is called when Shizuku starts, stops or answers the permission dialog. Close the result to stop listening. */
    fun addChangeListener(onChange: () -> Unit): AutoCloseable = AutoCloseable { }
}
