package com.example.phonesteward.logic

/**
 * a URI remembered from a previous run (restored
 * from an app-data backup, or naming a recycled MediaStore id) must never be
 * trusted by the trash path. Before acting, the adapter re-reads the row
 * behind the URI and this pure check must accept all three markers:
 *
 * - the row's owner package is this app;
 * - the row lives exactly in Pictures/PhoneStewardTest/;
 * - the row's display name starts with phone-steward-test-.
 *
 * Anything else — including a missing row (all nulls) — refuses.
 */
object TestItemCheck {

    const val TEST_FOLDER = "Pictures/PhoneStewardTest/"
    const val TEST_NAME_PREFIX = "phone-steward-test-"

    fun isOwnTestItem(
        ownerPackageName: String?,
        relativePath: String?,
        displayName: String?,
        appPackageName: String,
    ): Boolean =
        ownerPackageName == appPackageName &&
            relativePath == TEST_FOLDER &&
            displayName != null &&
            displayName.startsWith(TEST_NAME_PREFIX)
}
