package com.envi.wispr.ui

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.models.ModelBootstrapApplication
import com.envi.wispr.paste.AccessibilityPermission
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.AutoPasteReadiness
import com.envi.wispr.paste.PasteAccessibilityService
import com.envi.wispr.polish.MlKitLanguageDetector
import com.envi.wispr.polish.PolishFailureNotice
import com.envi.wispr.providers.ProviderConfigurationRepository
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.shortcuts.BubbleRequestToken
import com.envi.wispr.shortcuts.DictationNotificationController
import com.envi.wispr.shortcuts.DictationSurfaceState
import com.envi.wispr.vocabulary.CustomTermRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The Android shell around one dictation take: lifecycle, command parsing, foreground state, and the
 * concrete Android calls the owner needs, each one line. The take itself, its state machine and every
 * decision about it, lives in [DictationSessionCoordinator] (#186). This service stops itself after every
 * take, so the next command creates a fresh instance and a fresh coordinator that begins IDLE.
 *
 * Owns a dictation session without placing an Activity above the user's typing app.
 */
class DictationSessionService : Service() {
    companion object {
        const val ACTION_START = "com.envi.wispr.action.START_DICTATION"
        const val ACTION_TOGGLE = "com.envi.wispr.action.TOGGLE_DICTATION"
        const val ACTION_STOP = "com.envi.wispr.action.STOP_DICTATION"
        const val ACTION_CANCEL = "com.envi.wispr.action.CANCEL_DICTATION"
        private const val EXTRA_FOREGROUND_COMMAND = "foreground_command"

        /** The floating bubble's request token (`BubbleRequestToken.encode`), on START, STOP and CANCEL. */
        const val EXTRA_REQUEST = "bubble_request"

        /**
         * `TriggerSource.wire` on a START or TOGGLE: which surface asked (issue #176). A bubble request
         * carries its own answer in its token; every other surface names itself here or reads `unknown`.
         */
        const val EXTRA_TRIGGER_SOURCE = "trigger_source"

        internal fun sendCommand(context: Context, action: String, requestToken: String? = null, trigger: TriggerSource? = null) {
            val intent = Intent(context, DictationSessionService::class.java).setAction(action)
            if (requestToken != null) intent.putExtra(EXTRA_REQUEST, requestToken)
            if (trigger != null) intent.putExtra(EXTRA_TRIGGER_SOURCE, trigger.wire)
            if (action == ACTION_START || action == ACTION_TOGGLE) {
                intent.putExtra(EXTRA_FOREGROUND_COMMAND, true)
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Reads the dictation's language off the finished transcript for this side's deterministic fallback
     * (#107). Built in `onCreate`, not as a field initializer and NOT lazily, for the reason
     * `PolishService` gives: a field initializer has no context yet, and a `Lazy` reopens the
     * close-versus-first-use race above the detector's own lock. Constructing loads no model; the bundled
     * model is loaded on the first detection and released in `onDestroy`, which is what keeps this
     * long-running process free of a resident model between dictations.
     */
    private lateinit var languageDetector: MlKitLanguageDetector
    private lateinit var preferences: SessionPreferencesSource
    private lateinit var bindings: PipelineBindings
    private lateinit var coordinator: DictationSessionCoordinator

    /**
     * The Android calls the owner makes, each one line, built once in `onCreate` and handed to the coordinator;
     * not a field, so the Service holds exactly its five adapters (`SessionOwnerShapeTest`).
     */
    private fun createHost(): SessionHost = object : SessionHost {
        override fun promoteToForeground(processing: Boolean) =
            this@DictationSessionService.promoteToForeground(processing)

        override fun updateSurfacePhase(phase: DictationSurfaceState.Phase) {
            DictationSurfaceState.update(this@DictationSessionService, phase)
        }

        override fun vibrate(cue: HapticCue) = this@DictationSessionService.vibrate(cue)

        override fun toastFromService(line: String) {
            Toast.makeText(this@DictationSessionService, line, Toast.LENGTH_LONG).show()
        }

        override fun toastFromApplication(line: String) {
            Toast.makeText(applicationContext, line, Toast.LENGTH_LONG).show()
        }

        override fun showPolishNotice(notice: PolishFailureNotice) {
            DictationNotificationController.showPolishNotice(this@DictationSessionService, notice)
        }

        override fun copyToClipboard(text: String): Boolean = runCatching {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("EnviousWispr", text))
        }.isSuccess

        override fun autoPasteAvailability(): AutoPasteAvailability =
            this@DictationSessionService.autoPasteAvailability()

        override fun removeForegroundAndDismiss() {
            stopForeground(STOP_FOREGROUND_REMOVE)
            DictationNotificationController.dismiss(this@DictationSessionService)
        }

        override fun stopSelfNow() {
            stopSelf()
        }

        override fun postToMain(runnable: Runnable) {
            mainHandler.post(runnable)
        }

        override fun postToMainDelayed(delayMs: Long, runnable: Runnable) {
            mainHandler.postDelayed(runnable, delayMs)
        }

        override fun cancelMainDelayed(runnable: Runnable) {
            mainHandler.removeCallbacks(runnable)
        }

        override fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

        override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    }

    private fun promoteToForeground(processing: Boolean) {
        val notification = if (processing) {
            DictationNotificationController.processing(this)
        } else {
            DictationNotificationController.listening(
                context = this,
                autoPaste = autoPasteAvailability(),
                // The live field, not the session snapshot: this runs before `beginSession`
                // freezes one, and it is the field that snapshot is taken from. It is null on a
                // cold start, which is the state the notification has to be able to say nothing
                // about rather than guess at.
                clipboard = preferences.clipboardPolicy,
            )
        }
        startForeground(
            DictationNotificationController.NOTIFICATION_ID,
            notification,
            if (processing) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            },
        )
    }

    private fun vibrate(cue: HapticCue) {
        if (cue.honoursSystemHapticSetting &&
            Settings.System.getInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 1
        ) {
            return
        }
        runCatching {
            // VibratorManager is API 31 against minSdk 33. Guarded here as well as in
            // AccessibilityInsertionRunner.performResultHaptic: the runCatching only degrades to no
            // haptics at all on the oldest supported phone, which is a silent loss of every cue.
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            } ?: return
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(cue.durationMs, cue.amplitude))
            }
        }
    }

    /**
     * Liveness is a volatile read. The permission half is a `Settings.Secure` lookup, which the
     * platform serves from a per-process cache after the first call. Read only when a session
     * starts and when a dictation falls back, never at idle (`architecture-rules.md`
     * RULE: no-idle-cost). The setting alone cannot answer this: it still names a crashed service.
     */
    private fun autoPasteAvailability(): AutoPasteAvailability = AutoPasteReadiness.evaluate(
        permittedInSettings = AccessibilityPermission.isGranted(this),
        serviceBound = PasteAccessibilityService.isBound.value,
    )

    override fun onCreate() {
        super.onCreate()
        languageDetector = MlKitLanguageDetector(applicationContext)
        val customTermRepository = CustomTermRepository(applicationContext)
        val providerConfiguration = ProviderConfigurationRepository(applicationContext)
        preferences = SessionPreferencesSource(
            preferenceStates = AppPreferences(applicationContext).authoritativeState,
            terms = customTermRepository.observeTerms(),
            migrateLegacyTerms = { customTermRepository.migrateLegacySharedPreferences(applicationContext) },
            log = DebugSessionLog,
        )
        bindings = PipelineBindings(applicationContext, mainHandler, DebugSessionLog)
        val host = createHost()
        coordinator = DictationSessionCoordinator(
            host = host,
            surface = OverlayRecorderSurface,
            insertion = AccessibilityInsertionGateway,
            log = DebugSessionLog,
            preferences = preferences,
            historyWrites = ModelBootstrapApplication.historyWrites(applicationContext),
            transcripts = TranscriptRepository(EnviousWisprDatabase.get(applicationContext).transcriptDao()),
            languageDetector = languageDetector,
            loadPolicy = { providerConfiguration.loadPolicy() },
            pipeline = bindings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            mainDispatcher = Dispatchers.Main.immediate,
        )
        coordinator.onCreated()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND_COMMAND, false) == true) {
            promoteToForeground(coordinator.isProcessing)
        }
        val request = BubbleRequestToken.parse(intent?.getStringExtra(EXTRA_REQUEST))
        val trigger = TriggerSource.fromExtra(intent?.getStringExtra(EXTRA_TRIGGER_SOURCE))
        coordinator.handleCommand(intent?.action ?: ACTION_START, request, trigger)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::languageDetector.isInitialized) languageDetector.close()
        if (::coordinator.isInitialized) coordinator.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        DictationNotificationController.dismiss(this)
        super.onDestroy()
    }
}
