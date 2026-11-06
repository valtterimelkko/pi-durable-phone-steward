package com.example.phonesteward.testlab

import com.example.phonesteward.update.AppLatest
import com.example.phonesteward.update.UpdateRefusal
import com.example.phonesteward.update.UpdateUiState

/** Invented update states for the Status screen's update card (A7, F1). */
object UpdateFixtures {
    const val INSTALLED = "0.2.3"

    val latest = AppLatest(
        versionName = "0.2.4",
        versionCode = 6,
        sha256 = "0".repeat(64),
        sizeBytes = 26_500_000L,
        publishedAtMs = ScreenFixtures.REVIEW_AT_MS,
    )

    val upToDate: UpdateUiState = UpdateUiState.UpToDate(INSTALLED)
    val checking: UpdateUiState = UpdateUiState.Checking(INSTALLED)
    val checkFailed: UpdateUiState = UpdateUiState.CheckFailed(INSTALLED)
    val available: UpdateUiState = UpdateUiState.Available(INSTALLED, latest)
    val needsPermission: UpdateUiState = UpdateUiState.NeedsPermission(INSTALLED, latest)
    val downloading: UpdateUiState = UpdateUiState.Downloading(INSTALLED, latest, bytes = 11_400_000L, total = latest.sizeBytes)
    val verifying: UpdateUiState = UpdateUiState.Verifying(INSTALLED, latest)
    val installing: UpdateUiState = UpdateUiState.Installing(INSTALLED, latest)
    val refused: UpdateUiState = UpdateUiState.Refused(INSTALLED, latest, UpdateRefusal.SHA256_MISMATCH)
    val failed: UpdateUiState = UpdateUiState.Failed(INSTALLED, latest, "Cancelled. Nothing was changed.")
}
