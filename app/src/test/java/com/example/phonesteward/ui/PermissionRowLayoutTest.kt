package com.example.phonesteward.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.phonesteward.logic.Onboarding

/**
 * R1 regression: a permission row's button must never be squeezed by a long
 * label ("Settings" wrapping one letter per line). The two-line layout gives
 * the button its own line; this test renders the worst case at a narrow
 * width and asserts the button keeps a usable width.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PermissionRowLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the settings button keeps its width beside a long label`() {
        val step = Onboarding.Step.ALL_FILES
        compose.setContent {
            MaterialTheme {
                PermissionRow(
                    title = "${step.title} — needed so the steward can see every folder, " +
                        "including Android/media where WhatsApp and Telegram keep theirs",
                    description = step.how,
                    state = Onboarding.RowState.ACTION_NEEDED,
                    buttonText = "Open settings",
                    onButtonClick = {},
                )
            }
        }
        compose.onNodeWithText("Open settings").assertWidthIsAtLeast(120.dp)
    }

    // Owner rule, changed on 2026-10-08: a granted row shows NO button at all. The earlier R1 rule
    // ("a granted row disables its button but keeps it visible") left a greyed control that looked dead.
    // The green tick and "Allowed" stay; the button comes back if the permission is revoked.
    @Test
    fun `a granted row shows no button, only the tick and Allowed`() {
        compose.setContent {
            MaterialTheme {
                PermissionRow(
                    title = "Usage access",
                    description = "granted already",
                    state = Onboarding.RowState.GRANTED,
                    buttonText = "Open settings",
                    onButtonClick = {},
                )
            }
        }
        compose.onNodeWithText("Open settings").assertDoesNotExist()
        compose.onNodeWithText("Allowed").assertExists()
        compose.onNodeWithContentDescription("Allowed").assertExists()
        compose.onNodeWithText("Usage access").assertExists()
    }

    @Test
    fun `the button returns, enabled, when a granted permission is revoked`() {
        var state by mutableStateOf(Onboarding.RowState.GRANTED)
        compose.setContent {
            MaterialTheme {
                PermissionRow(
                    title = "Usage access",
                    description = "needed",
                    state = state,
                    buttonText = "Open settings",
                    onButtonClick = {},
                )
            }
        }
        compose.onNodeWithText("Open settings").assertDoesNotExist()
        state = Onboarding.RowState.ACTION_NEEDED
        compose.waitForIdle()
        compose.onNodeWithText("Open settings").assertExists().assertIsEnabled()
        state = Onboarding.RowState.GRANTED
        compose.waitForIdle()
        compose.onNodeWithText("Open settings").assertDoesNotExist()
    }

    @Test
    fun `a not-needed row is visibly distinct with a neutral label and disabled button`() {
        compose.setContent {
            MaterialTheme {
                PermissionRow(
                    title = Onboarding.Step.MANAGE_MEDIA.title,
                    description = Onboarding.Step.MANAGE_MEDIA.how,
                    state = Onboarding.RowState.NOT_NEEDED,
                    buttonText = "Allow managing media",
                    onButtonClick = {},
                )
            }
        }
        // The neutral label replaces the action button; the action text is gone.
        compose.onNodeWithText("Not needed now").assertExists()
        compose.onNodeWithText("Allow managing media").assertDoesNotExist()
    }
}
