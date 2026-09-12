package com.envi.wispr.ui

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.settings.AppPreferences
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PracticePhase { IDLE, STARTING, RECORDING, PROCESSING }

/** Owns only onboarding interaction state; engines and delivery remain in their existing owners. */
internal class OnboardingViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val preferences = AppPreferences(context)
    private val refresh = MutableStateFlow(0)
    private val work = WorkManager.getInstance(context)
    private var requestToken: String? = null
    var practicePhase by mutableStateOf(PracticePhase.IDLE)
        private set
    var draft by mutableStateOf(TextFieldValue(saved.get<String>("practice_draft").orEmpty()))
        private set
    var practiceComplete by mutableStateOf(saved.get<Boolean>("practice_complete") ?: false)
        private set
    var practiceMessage by mutableStateOf(if (saved.get<Boolean>("practice_active") == true) "Practice was interrupted. Your text is saved. Try again." else "")
        private set
    var downloadMessage by mutableStateOf("")
        private set
    private var insertionDraft = draft
    val practicing: Boolean get() = practicePhase != PracticePhase.IDLE

    init { saved["practice_active"] = false }

    private fun modelFlow(model: com.envi.wispr.models.ModelDescriptor): kotlinx.coroutines.flow.Flow<ModelUiState> {
        val observations = combine(
            work.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.downloadWorkName(model)),
            work.getWorkInfosForUniqueWorkFlow(ModelDeliveryWorker.adoptionWorkName(model)),
        ) { download, adoption -> preferredModelWork(download, adoption) }
        return observeSetupModelProgress(observations, refresh, viewModelScope,
            verify = { ModelStorage.isReady(context, model) },
            project = { info, ready ->
                val state = workUiState(info, ready, model, context)
                if (state.action == com.envi.wispr.models.ModelUiAction.RESUME) state.copy(
                    bytes = com.envi.wispr.models.ModelDeliveryStore(ModelStorage.root(context)).stagedBytes(model),
                    total = model.files.sumOf { it.expectedBytes },
                ) else state
            })
    }

    val downloads = combine(modelFlow(ModelManifest.parakeet), modelFlow(ModelManifest.s1)) { speech, polish -> listOf(speech, polish) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startSetup() {
        viewModelScope.launch {
            // Navigation is committed before worker admission. No late write can undo Back.
            preferences.setOnboardingStep(OnboardingStage.DOWNLOADS.ordinal)
            downloadAction {
                val mobile = preferences.authoritativeState.first().onboardingMobileData
                ModelManifest.all.forEach { ModelDeliveryWorker.enqueueSetup(context, it, mobile) }
            }
        }
    }

    fun resumeDownloads(mobileData: Boolean? = null) = downloadAction {
        if (mobileData != null) preferences.setOnboardingMobileData(mobileData)
        val mobile = preferences.authoritativeState.first().onboardingMobileData
        ModelManifest.all.forEach { ModelDeliveryWorker.enqueueSetup(context, it, mobile, restart = true) }
    }

    fun pauseDownloads() = downloadAction {
        ModelManifest.all.filterIndexed { index, _ -> downloads.value.getOrNull(index)?.health != ModelHealth.READY }.forEach { ModelDeliveryWorker.pause(context, it) }
    }

    private fun downloadAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            downloadMessage = ""
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { downloadMessage = "Couldn’t update the download. Please try again." }
            refresh.value++
        }
    }

    fun editDraft(value: TextFieldValue) {
        if (practicing) return
        draft = value
        saved["practice_draft"] = value.text
    }

    fun startPractice() {
        if (practicing) return
        val token = UUID.randomUUID().toString()
        requestToken = token
        insertionDraft = draft
        practicePhase = PracticePhase.STARTING
        practiceMessage = ""
        saved["practice_active"] = true
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (requestToken != token || resultData?.getString(PracticeDelivery.TOKEN) != token) return
                when (resultCode) {
                    PracticeDelivery.STARTING -> practicePhase = PracticePhase.STARTING
                    PracticeDelivery.RECORDING -> practicePhase = PracticePhase.RECORDING
                    PracticeDelivery.PROCESSING -> practicePhase = PracticePhase.PROCESSING
                    PracticeDelivery.FINISHED -> {
                        val result = resultData.getString(PracticeDelivery.TEXT).orEmpty()
                        if (result.isNotBlank()) {
                            val (text, caret) = mergePracticeText(insertionDraft.text, insertionDraft.selection.start, insertionDraft.selection.end, result)
                            draft = TextFieldValue(text, TextRange(caret))
                            saved["practice_draft"] = text
                            practiceComplete = true
                            saved["practice_complete"] = true
                        }
                        endPractice(if (result.isBlank()) "No words were detected. Try again." else "")
                    }
                    PracticeDelivery.ENDED -> endPractice("No new text was added. Try again when you’re ready.")
                    PracticeDelivery.ERROR -> endPractice(resultData.getString(PracticeDelivery.TEXT) ?: "Couldn’t finish that dictation. Please try again.")
                }
            }
        }
        runCatching { PracticeDelivery.start(context, token, receiver) }
            .onFailure { endPractice("Couldn’t start recording. Please try again.") }
    }

    fun stopPractice() { requestToken?.let { PracticeDelivery.command(context, it, DictationSessionService.ACTION_STOP) } }
    fun cancelPractice() { requestToken?.let { runCatching { PracticeDelivery.command(context, it, DictationSessionService.ACTION_CANCEL) } } }

    private fun endPractice(message: String) {
        requestToken = null
        practicePhase = PracticePhase.IDLE
        saved["practice_active"] = false
        practiceMessage = message
    }

    override fun onCleared() {
        cancelPractice()
        super.onCleared()
    }
}
