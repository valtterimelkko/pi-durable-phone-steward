package com.example.phonesteward.android

import android.content.Context
import com.example.phonesteward.logic.chat.ChatSettings
import com.example.phonesteward.logic.chat.ChatVerbosity

/** F2: the Chat verbosity, kept in the app's private preferences so it survives a restart. Default Medium. */
class PrefsChatSettings(context: Context) : ChatSettings {

    private val prefs = context.getSharedPreferences("phone_steward_chat", Context.MODE_PRIVATE)

    override fun read(): ChatVerbosity = ChatVerbosity.fromName(prefs.getString(KEY, null))

    override fun write(level: ChatVerbosity) {
        prefs.edit().putString(KEY, level.name).apply()
    }

    private companion object {
        const val KEY = "verbosity"
    }
}
