package com.envi.wispr.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.models.ModelUiState
import com.envi.wispr.paste.OwnFieldAdmission
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.shortcuts.RecordingOverlayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns only onboarding interaction state; engines and delivery remain in their existing owners.
 *
 * Practice is a REAL dictation: the floating lips appear beside the practice box because the box is
 * the one field of our own the accessibility service admits ([OwnFieldAdmission]), a tap or a hold
 * on them runs the session owner exactly as a Gmail field would, and the words land through the
 * normal insertion path. This model only watches: the owner's published phase for the headline, and
 * the History row the take wrote for the verdict ([judgePracticeTake]).
 */
internal class OnboardingViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val preferences = AppPreferences(context)
    private val refresh = MutableStateFlow(0)
    private val work = WorkManager.getInstance(context)
    private val transcripts by lazy { TranscriptRepository(EnviousWisprDatabase.get(context).transcriptDao()) }
    var practicePhase by mutableStateOf(RecordingOverlayState.Phase.IDLE)
        private set
    var lesson by mutableStateOf(if (saved.get<Boolean>("practice_hold_lesson") == true) PracticeLesson.HOLD else PracticeLesson.TAP)
        private set
    var draft by mutableStateOf(TextFieldValue(saved.get<String>("practice_draft").orEmpty()))
        private set
    /** The tap lesson landed once: Finish setup is available. */
    var practiceComplete by mutableStateOf(saved.get<Boolean>("practice_complete") ?: false)
        private set
    var holdComplete by mutableStateOf(saved.get<Boolean>("practice_hold_complete") ?: false)
        private set
    /** The verdict on the last take, or null before the first take and while one is running. */
    var practiceOutcome by mutableStateOf<PracticeOutcome?>(null)
        private set
    /** The gesture behind the take in progress, or null when there is none or it carried no bubble token. */
    var takeHeld by mutableStateOf<Boolean?>(null)
        private set
    var downloadMessage by mutableStateOf("")
        private set
    private var take: PracticeTake? = null
    private var rows: List<TranscriptEntity> = emptyList()
    private var watching: Job? = null
    val practicing: Boolean get() = practicePhase != RecordingOverlayState.Phase.IDLE || practiceOutcome == PracticeOutcome.WORKING

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

    /** Every edit is accepted, the words the service puts in the box included: the box is a real editor. */
    fun editDraft(value: TextFieldValue) {
        val changed = value.text != draft.text
        draft = value
        saved["practice_draft"] = value.text
        if (changed) judge()
    }

    /**
     * The practice box is on screen AND in front: admit it to the accessibility service and follow the
     * owner and History until [leavePractice]. Called on resume and undone on pause, so a take the user
     * makes in another app while setup waits in the background is never adopted as practice (Codex
     * review, 2026-09-14). Idempotent, so a recomposition costs nothing.
     */
    fun enterPractice() {
        if (watching?.isActive == true) return
        OwnFieldAdmission.admit(PRACTICE_FIELD_ID)
        watching = viewModelScope.launch {
            launch { RecordingOverlayState.snapshots.collect { snapshot -> followOwner(snapshot) } }
            launch { transcripts.transcripts.collect { latest -> rows = latest; judge() } }
        }
    }

    fun leavePractice() {
        watching?.cancel()
        watching = null
        OwnFieldAdmission.withdraw(PRACTICE_FIELD_ID)
    }

    /** After the tap lesson landed: teach the hold. */
    fun startHoldLesson() {
        if (practicing) return
        lesson = PracticeLesson.HOLD
        saved["practice_hold_lesson"] = true
        take = null
        practiceOutcome = null
    }

    private fun followOwner(snapshot: RecordingOverlayState.Snapshot) {
        practicePhase = snapshot.phase
        val current = take
        take = if (snapshot.phase != RecordingOverlayState.Phase.IDLE) {
            val processing = snapshot.phase == RecordingOverlayState.Phase.PROCESSING
            if (current == null || current.ended) {
                PracticeTake(startedAtMs = System.currentTimeMillis(), held = snapshot.requestToken?.held, boxTextAtStart = draft.text, processed = processing)
            } else {
                current.copy(held = current.held ?: snapshot.requestToken?.held, processed = current.processed || processing)
            }
        } else {
            current?.copy(ended = true)
        }
        takeHeld = take?.takeIf { !it.ended }?.held
        judge()
    }

    private fun judge() {
        val current = take ?: return
        val outcome = judgePracticeTake(current, lesson, rows, draft.text)
        practiceOutcome = outcome
        when (outcome) {
            PracticeOutcome.LANDED -> if (lesson == PracticeLesson.HOLD) {
                holdComplete = true
                saved["practice_hold_complete"] = true
            } else {
                practiceComplete = true
                saved["practice_complete"] = true
            }
            // A tap answered the hold lesson: still a landed take, so Finish setup is earned.
            PracticeOutcome.LANDED_BY_TAP -> {
                practiceComplete = true
                saved["practice_complete"] = true
            }
            else -> Unit
        }
    }

    override fun onCleared() {
        leavePractice()
        super.onCleared()
    }

    companion object {
        /** The practice box's accessibility view id: its Compose test tag, exported as a resource id. */
        const val PRACTICE_FIELD_ID = "envious_practice_field"
    }
}
