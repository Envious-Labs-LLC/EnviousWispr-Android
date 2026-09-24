package com.envi.wispr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun LicensesPage(notices: String) {
    ScreenContainer(subtitle = SettingsPage.Licenses.subtitle) {
        Text(
            notices,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
