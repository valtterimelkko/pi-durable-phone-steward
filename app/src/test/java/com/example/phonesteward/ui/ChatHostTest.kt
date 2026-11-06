package com.example.phonesteward.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.testlab.ChatChecks

/** The shared F2 Chat checks on the host (Robolectric); the same checks run on Test Lab devices. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class ChatHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun low() = ChatChecks.checkLevel(compose, ChatVerbosity.Low)
    @Test fun medium() = ChatChecks.checkLevel(compose, ChatVerbosity.Medium)
    @Test fun high() = ChatChecks.checkLevel(compose, ChatVerbosity.High)
    @Test fun workingLow() = ChatChecks.checkWorking(compose, ChatVerbosity.Low)
    @Test fun workingMedium() = ChatChecks.checkWorking(compose, ChatVerbosity.Medium)
    @Test fun verbosityControl() = ChatChecks.checkVerbosityControl(compose)
    @Test fun messageBox() = ChatChecks.checkMessageBox(compose)
}
