package com.envi.wispr.ui

import androidx.work.WorkInfo
import com.envi.wispr.models.ModelUiState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

private data class VerificationKey(val id: UUID?, val state: WorkInfo.State?, val generation: Int)
private data class Verification(val key: VerificationKey, val ready: Boolean)

/** File verification has its own producer, so a blocking hash/operation cannot hold up progress. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun observeSetupModelProgress(
    work: Flow<WorkInfo?>,
    refresh: Flow<Int>,
    scope: CoroutineScope,
    verify: () -> Boolean,
    project: (WorkInfo?, Boolean) -> ModelUiState,
): Flow<ModelUiState> {
    val observations = combine(work, refresh) { info, generation -> info to generation }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    val verification = observations.map { (info, generation) -> VerificationKey(info?.id, info?.state, generation) }.distinctUntilChanged()
        .mapLatest { key ->
            val ready = if (key.state != null && !key.state.isFinished) false
            else withContext(Dispatchers.IO) { verify() }
            Verification(key, ready)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
    return combine(observations, verification) { (info, generation), checked ->
        val key = VerificationKey(info?.id, info?.state, generation)
        withContext(Dispatchers.IO) { project(info, checked?.key == key && checked.ready) }
    }
}
