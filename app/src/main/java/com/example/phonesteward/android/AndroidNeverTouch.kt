package com.example.phonesteward.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.example.phonesteward.logic.shizuku.NeverTouch

/** The never-touch list with the two things only the phone knows: the keyboard in use and the home app (A10.2). */
object AndroidNeverTouch {
    fun of(context: Context): NeverTouch {
        val app = context.applicationContext
        return NeverTouch(
            keyboardInUse = {
                try {
                    Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                        ?.substringBefore('/')?.takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
            },
            launcher = {
                try {
                    app.packageManager.resolveActivity(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
                    )?.activityInfo?.packageName
                } catch (_: Exception) {
                    null
                }
            },
        )
    }
}
