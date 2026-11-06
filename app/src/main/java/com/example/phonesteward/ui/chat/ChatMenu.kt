package com.example.phonesteward.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The Chat top bar's overflow menu (A10.9): Compact now, and Start fresh (which asks once). */
@Composable
fun ChatMenu(onCompactNow: () -> Unit, onStartFresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // One box, so the menu opens under its own button.
    androidx.compose.foundation.layout.Box {
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("chat-menu")) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Chat menu")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Compact now") },
            onClick = { open = false; onCompactNow() },
            modifier = Modifier.testTag("menu-compact"),
        )
        DropdownMenuItem(
            text = { Text("Start fresh") },
            onClick = { open = false; onStartFresh() },
            modifier = Modifier.testTag("menu-fresh"),
        )
    }
    }
}
