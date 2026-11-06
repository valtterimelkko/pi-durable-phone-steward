package com.example.phonesteward.screen

/**
 * A15.11: whether a refusal may be put to the owner as "allow this app for this task".
 *
 * Only the missing allow-list entry is offerable. Never an always-denied app (the built-in
 * floor, A14.3), never the steward's own screen, never a sensitive screen, and never an app
 * that is already allowed or explicitly denied.
 */
object AllowForTask {

    fun offerable(pkg: String, access: GuardPolicy.Access, sensitiveScreen: Boolean): Boolean =
        access == GuardPolicy.Access.NOT_LISTED &&
            !GuardPolicy.alwaysDenied(pkg) &&
            !sensitiveScreen
}
