package com.envi.wispr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The whole app on a phone `DeviceTier` blocks (#449 chunk 2): no setup, no Settings, no dictation button, so nothing
 * here can start a download, a model load or a take. Copy reviewed in the plan (grounded round 1).
 */
internal object UnsupportedPhoneCopy {
    const val TITLE = "This phone isn't supported"
    const val BODY = "This phone's processor is outside the range EnviousWispr currently supports. Dictation and model downloads are turned off on this phone."
    const val START_REFUSED = "EnviousWispr isn't supported on this phone"
    const val MID_TIER_NOTICE = "Dictation and on-device AI polish may be slow on this phone."
}

@Composable
internal fun UnsupportedPhoneScreen() {
    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(UnsupportedPhoneCopy.TITLE, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(UnsupportedPhoneCopy.BODY, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
    }
}
