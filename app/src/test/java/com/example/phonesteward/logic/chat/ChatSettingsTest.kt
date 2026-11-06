package com.example.phonesteward.logic.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.phonesteward.android.PrefsChatSettings

/** F2: the verbosity choice is saved on the phone and survives a restart; the default is Medium. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ChatSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun defaultsToMedium() {
        context.getSharedPreferences("phone_steward_chat", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(ChatVerbosity.Medium, PrefsChatSettings(context).read())
    }

    @Test fun aSavedChoiceSurvivesANewInstance() {
        PrefsChatSettings(context).write(ChatVerbosity.High)
        assertEquals(ChatVerbosity.High, PrefsChatSettings(context).read())
        PrefsChatSettings(context).write(ChatVerbosity.Low)
        assertEquals(ChatVerbosity.Low, PrefsChatSettings(context).read())
    }

    @Test fun aCorruptValueFallsBackToMedium() {
        context.getSharedPreferences("phone_steward_chat", Context.MODE_PRIVATE).edit().putString("verbosity", "???").commit()
        assertEquals(ChatVerbosity.Medium, PrefsChatSettings(context).read())
    }
}
