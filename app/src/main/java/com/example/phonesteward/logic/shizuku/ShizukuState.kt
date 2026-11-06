package com.example.phonesteward.logic.shizuku

/** `permissions.shizuku` (A10.2). */
enum class ShizukuState(val wire: String) {
    ABSENT("absent"),
    NOT_RUNNING("not_running"),
    NO_PERMISSION("no_permission"),
    READY("ready"),
    ;

    companion object {
        /**
         * @param installed the Shizuku app is on the phone
         * @param binderAlive its service is running and reachable
         * @param permissionGranted the owner allowed the steward to use it (only meaningful while the service runs)
         */
        fun of(installed: Boolean, binderAlive: Boolean, permissionGranted: Boolean): ShizukuState = when {
            binderAlive && permissionGranted -> READY
            binderAlive -> NO_PERMISSION
            installed -> NOT_RUNNING
            else -> ABSENT
        }
    }
}
