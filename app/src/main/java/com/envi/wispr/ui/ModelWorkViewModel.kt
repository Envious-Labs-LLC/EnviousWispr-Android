package com.envi.wispr.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings shell's two model cards (#255): Parakeet on Transcription, S1-mini on AI Polish. */
internal data class ModelWorkUiState(
    val speech: ModelUiState = ModelWorkViewModel.CHECKING,
    val polish: ModelUiState = ModelWorkViewModel.CHECKING,
)

/**
 * The settings shell's one owner of model-delivery observation (#255). A model is observed only while its tab
 * shows and the activity is started ([show]); its WorkManager work and verified readiness are projected on
 * [io], never during composition, and published on main. Each activation starts at [CHECKING], so a card
 * cached from before a hidden download, removal or repair is never shown as current. [finished] is the
 * always-on signal that some model work finished, which the activity turns into its readiness refresh.
 * Onboarding keeps its own pipeline (`OnboardingViewModel`), which also verifies files and stages bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ModelWorkViewModel(
    /** One unique work's infos by name; production `WorkManager.getWorkInfosForUniqueWorkFlow`. */
    private val work: (String) -> Flow<List<WorkInfo>>,
    /** `ReadinessViewModel.state`: the verified-ready facts, read once they are loaded. */
    private val readiness: Flow<ReadinessUiState>,
    /** `workUiState`, which reads model storage; always called on [io]. */
    private val project: (WorkInfo?, Boolean, ModelDescriptor) -> ModelUiState,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    companion object {
        /** The neutral card before a fresh projection lands: no model action. */
        val CHECKING = ModelUiState("Checking", ModelHealth.UNKNOWN)

        /** The four works whose finish can change what is installed. */
        private val WATCHED = listOf(
            ModelDeliveryWorker.downloadWorkName(ModelManifest.parakeet),
            ModelDeliveryWorker.downloadWorkName(ModelManifest.s1),
            ModelDeliveryWorker.adoptionWorkName(ModelManifest.parakeet),
            ModelDeliveryWorker.adoptionWorkName(ModelManifest.s1),
        )
    }

    private val published = MutableStateFlow(ModelWorkUiState())
    val models: StateFlow<ModelWorkUiState> = published.asStateFlow()
    private var activation: Job? = null

    /**
     * The visible destination, or null for none (a settings page, onboarding, a stopped activity). Main only.
     * Cancels the previous activation before resetting, so a late projection can never land on a newer
     * tab's state; idempotent for null, because the caller's cleanup can run more than once.
     */
    fun show(destination: AppDestination?) {
        activation?.cancel()
        activation = null
        published.value = ModelWorkUiState()
        val model = when (destination) {
            AppDestination.Polish -> ModelManifest.s1
            AppDestination.Transcription -> ModelManifest.parakeet
            AppDestination.History, AppDestination.Dictionary, null -> return
        }
        activation = viewModelScope.launch {
            observe(model)
                // A failed observation shows the neutral card; the next activation retries.
                .catch { error -> if (error is CancellationException) throw error else emit(CHECKING) }
                .collect { state -> publish(model, state) }
        }
    }

    private fun observe(model: ModelDescriptor): Flow<ModelUiState> = combine(
        work(ModelDeliveryWorker.downloadWorkName(model)),
        work(ModelDeliveryWorker.adoptionWorkName(model)),
        readiness.filter { it.loaded }.map { if (model == ModelManifest.s1) it.readiness.polishModelReady else it.readiness.speechModelReady }.distinctUntilChanged(),
    ) { download, adoption, ready -> preferredModelWork(download, adoption) to ready }
        .mapLatest { (info, ready) -> withContext(io) { project(info, ready, model) } }

    private fun publish(model: ModelDescriptor, state: ModelUiState) {
        published.update { if (model == ModelManifest.s1) it.copy(polish = state) else it.copy(speech = state) }
    }

    /**
     * One signal per new set of finished works, across every item of each watched chain (WorkManager does not
     * promise the newly finished item is first), so a later finish is not lost behind an older finished work
     * and a repeated snapshot signals nothing. Cold: the activity collects it while started ([collectModelRefresh]).
     */
    val finished: Flow<Unit> = combine(WATCHED.map(work)) { lists ->
        lists.flatMap { infos -> infos.filter { it.state.isFinished } }
            .map { it.id to it.state }
            .toSet()
    }.distinctUntilChanged().filter { it.isNotEmpty() }.map { }

    class Factory(
        private val appContext: Context,
        private val readiness: Flow<ReadinessUiState>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ModelWorkViewModel::class.java))
            val workManager = WorkManager.getInstance(appContext)
            return ModelWorkViewModel(
                work = { name -> workManager.getWorkInfosForUniqueWorkFlow(name) },
                readiness = readiness,
                project = { info, ready, model -> workUiState(info, ready, model, appContext) },
            ) as T
        }
    }
}

/**
 * Refreshes readiness on every [signal]. If the observation fails, refreshes once and stops; the activity's
 * next start collects again (#255).
 */
internal suspend fun collectModelRefresh(signal: Flow<Unit>, refresh: () -> Unit) {
    signal
        .catch { error -> if (error is CancellationException) throw error else refresh() }
        .collect { refresh() }
}
