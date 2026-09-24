package com.envi.wispr.ui

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.envi.wispr.models.ModelFolderFootprint
import com.envi.wispr.models.ModelFootprint
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the models folder is taking up on this phone, and where it goes.
 *
 * Storage is the single biggest cost of having this app installed, and until #20 the app said nothing
 * about it anywhere. The model cards answer it per model; this answers it for the folder, which is the
 * question a user trying to free space actually has.
 *
 * **The rows and the total come from ONE traversal**, in `ModelFootprint.measureFolder`. Walking each
 * model and then walking the folder is N+1 measurements at N+1 instants, and a model that grows between
 * two of them invents space "no model claims" that nobody is using. Review found exactly that in the
 * first version of this page, which held both numbers in one object and called that one measurement.
 *
 * Three states, not two. A failed walk used to collapse into the same null as a running one, so the page
 * said "Measuring" forever with nothing left to measure.
 */
@Composable
internal fun StoragePage() {
    val context = LocalContext.current
    // `key(Unit)` is not what makes this re-measure; leaving the page disposes the state and returning
    // starts a fresh producer. It is here so that a future key can be added without moving the holder.
    val reading by key(Unit) {
        produceState<StorageReading>(initialValue = StorageReading.Measuring) {
            value = withContext(Dispatchers.IO) {
                try {
                    StorageReading.Measured(
                        ModelFootprint.measureFolder(ModelStorage.root(context), ModelManifest.all),
                    )
                } catch (cancellation: CancellationException) {
                    // Cancellation is the page going away, not a measurement failure. Rethrowing keeps
                    // structured concurrency honest instead of reporting an error nobody will see.
                    throw cancellation
                } catch (_: Throwable) {
                    // `measureFolder` throws rather than returning a smaller number, so there is no
                    // partial figure to show and the honest answer is that we could not measure.
                    StorageReading.Failed
                }
            }
        }
    }

    ScreenContainer(subtitle = SettingsPage.Storage.subtitle) {
        when (val state = reading) {
            StorageReading.Measuring -> Text(
                "Measuring what is on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StorageReading.Failed -> Text(
                "Couldn't measure model storage. Leave this page and open it again to retry.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is StorageReading.Measured -> {
                val footprint = state.footprint
                SettingsGroup("Models") {
                    ModelManifest.all.forEachIndexed { index, model ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                        val bytes = footprint.perModel[model] ?: 0L
                        StorageRow(
                            title = model.displayName,
                            value = if (bytes > 0L) formatModelBytes(bytes) else "Not on this phone",
                        )
                    }
                    if (footprint.unclaimed > 0L) {
                        HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                        // Its own row rather than folded into a model, because no model owns it. This is
                        // what a half-finished download, or a file a version bump left behind, looks like.
                        StorageRow(
                            title = "Files no model claims",
                            value = formatModelBytes(footprint.unclaimed),
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    StorageRow(title = "Total", value = formatModelBytes(footprint.total), emphasise = true)
                }
                Text(
                    "This total covers the models folder, including files outside known model folders. " +
                        "It excludes the app itself and data stored elsewhere. Removing a model can " +
                        "free space, and you can download it again later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the Storage page knows so far. A sealed type because the third state is the one that went wrong:
 * a failure and a measurement still running are not the same thing and must not render the same way.
 */
internal sealed interface StorageReading {
    data object Measuring : StorageReading
    data object Failed : StorageReading
    data class Measured(val footprint: ModelFolderFootprint) : StorageReading
}

@Composable
private fun StorageRow(title: String, value: String, emphasise: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = if (emphasise) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        )
        Text(
            value,
            style = if (emphasise) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasise) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
