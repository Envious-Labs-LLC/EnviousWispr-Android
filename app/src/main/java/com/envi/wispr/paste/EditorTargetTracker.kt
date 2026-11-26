package com.envi.wispr.paste

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.envi.wispr.debug.DebugLogger

/** The remembered editor's app and password flag, for the bubble only (#523); content-free. */
internal data class FieldTraits(val packageName: String, val passwordField: Boolean)

/**
 * The blocking half of the bubble's field check ([EditorTargetTracker.fieldProbe]): run it OFF the main thread.
 * [windows] is the check's one window-list read, reused for every window-focus question but the last.
 */
internal fun interface FieldProbe {
    fun probe(discover: Boolean, windows: List<AccessibilityWindowInfo>): FieldAdoption
}

/** The main-thread half: applies what [FieldProbe] read, unless the remembered editor changed meanwhile. */
internal fun interface FieldAdoption {
    fun adopt(): FieldProbeOutcome
}

/** What adopting a probe's answer did. */
internal sealed interface FieldProbeOutcome {
    /** The remembered editor changed while the probe ran; nothing was applied. */
    data object Stale : FieldProbeOutcome

    /** The focused field's key, or null when no editor is focused. */
    data class Current(val key: Any?) : FieldProbeOutcome
}

/** What adopting a probe's candidate editor does, on main. Owner: [fieldAdoptionStep]. */
internal enum class FieldAdoptionStep { STALE, ADOPT, NO_FIELD }

/**
 * The adoption decision (#561). [epochCurrent]: the remembered editor is still the one the probe copied.
 * [candidateAdmitted]: null when the probe found no editor, else whether the candidate (the refreshed
 * remembered editor or a discovered one) still passes [OwnFieldAdmission] read ON MAIN, so a field of our
 * own withdrawn while the probe ran is never adopted on the worker's earlier answer.
 */
internal fun fieldAdoptionStep(epochCurrent: Boolean, candidateAdmitted: Boolean?): FieldAdoptionStep = when {
    !epochCurrent -> FieldAdoptionStep.STALE
    candidateAdmitted == true -> FieldAdoptionStep.ADOPT
    else -> FieldAdoptionStep.NO_FIELD
}

/**
 * Which editor a dictation aims at (#217): the editor the user last focused (remembered), and the editor a
 * take pinned (the one its words go to). Both the pin and the floating bubble ask this one owner the same
 * question, which editor is focused in the window that holds input focus, so they cannot disagree.
 *
 * Every call runs on the service's main thread, except the blocking half of the bubble's check
 * ([fieldProbe]), which runs off main and touches none of this state. The node copies this holds are its
 * own: it recycles them, and a caller reads the pinned editor only through [withPinnedNode] and the three
 * read-only properties.
 */
internal class EditorTargetTracker(
    private val service: AccessibilityService,
    private val onFieldObserved: (FieldTraits?) -> Unit = {},
) {

    private companion object {
        const val TAG = "PasteService"
    }

    private data class TargetSnapshot(
        val node: AccessibilityNodeInfo,
        val packageName: String,
        val windowId: Int,
        val className: String,
        val viewId: String?,
        val capturedAtMs: Long,
    )

    private data class TargetToken(
        val node: AccessibilityNodeInfo,
        val packageName: String,
        val windowId: Int,
        val className: String,
        val viewId: String?,
    )

    private data class FieldKey(val windowId: Int, val node: Int)

    /**
     * Bumped by EVERY write of [lastTarget], so the bubble's off-main probe can tell that the editor it
     * copied is no longer the remembered one ([fieldProbe]). Main thread only, like the target itself.
     */
    private var targetEpoch = 0
    private var lastTarget: TargetSnapshot? = null
        set(value) {
            field = value
            targetEpoch++
        }
    private var pinnedTarget: TargetToken? = null

    /** The pinned editor's package, or null when nothing is pinned. */
    val pinnedPackage: String? get() = pinnedTarget?.packageName

    /** The pinned editor's window, or null when nothing is pinned. */
    val pinnedWindowId: Int? get() = pinnedTarget?.windowId

    /** The accessibility view id of the pinned editor, or null when nothing is pinned or it has none. */
    val pinnedViewId: String? get() = pinnedTarget?.viewId

    /** True when the event named a new editable target and it was remembered. */
    fun rememberEditableTarget(event: AccessibilityEvent): Boolean {
        val eventPackage = event.packageName?.toString().orEmpty()
        if (!OwnFieldAdmission.searches(service.packageName, eventPackage)) return false

        val source = event.source ?: return false
        try {
            val shouldTrack = source.isEditable &&
                OwnFieldAdmission.accepts(service.packageName, eventPackage, source.viewIdResourceName) &&
                (source.isFocused ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)
            if (!shouldTrack) return false

            val snapshot = TargetSnapshot(
                node = AccessibilityNodeInfo.obtain(source),
                packageName = eventPackage,
                windowId = source.windowId,
                className = source.className?.toString().orEmpty(),
                viewId = source.viewIdResourceName,
                capturedAtMs = SystemClock.elapsedRealtime(),
            )
            clearTarget()
            lastTarget = snapshot
            notifyObservedField()
            DebugLogger.debug(
                TAG,
                "Remembered editable target package=${snapshot.packageName} " +
                    "window=${snapshot.windowId} class=${source.className}",
            )
            return true
        } finally {
            source.recycle()
        }
    }

    /**
     * Pins the editor this dictation should return to, and NAMES its own outcome.
     *
     * A Boolean here was read by the caller as "no editor was focused", which is only one of the two
     * ways this declines. Refusing while an insertion is still pending is a dictation started on top
     * of another one, and the words of the second are the ones at risk; classifying it as the
     * ordinary no-editor case suppressed the only sentence that user would have seen. The type is
     * the fix rather than a second matcher at the call site: a new exit here has to say which it is
     * (`workflow-process.md` RULE: enumerate-from-the-producer-not-from-the-findings).
     *
     * [insertionPending] is the insertion runner's answer, read by the service on main: the one state
     * of another owner the pin depends on (#217).
     */
    fun pinTarget(insertionPending: Boolean): DictationTargetPin {
        if (insertionPending) return DictationTargetPin.INSERTION_BUSY
        pinnedTarget?.let { existing ->
            if (existing.node.refresh() && isSafeFocusedEditor(existing.node) && isInFocusedWindow(existing.windowId)) {
                return DictationTargetPin.PINNED
            }
            clearPinnedTarget()
        }

        // The window-focus check matches the bubble's revalidation: a remembered editor in the app the user
        // just left keeps its own focus flag, so node focus alone would pin the departed field and the
        // words would land there. Only reuse a target whose window still owns input focus; otherwise
        // rediscover the one that does (Codex review, BUG 1, 2026-09-13).
        var target = lastTarget
        if (target == null || !target.node.refresh() || !isSafeFocusedEditor(target.node) || !isInFocusedWindow(target.windowId)) {
            clearTarget()
            target = findFocusedEditableTarget()
            lastTarget = target
            notifyObservedField()
        }
        target ?: return DictationTargetPin.NO_TARGET
        return pinResolvedTarget(target, "original")
    }

    /**
     * Pins an editor for a History recovery insertion (#17).
     *
     * Unlike [pinTarget], EnviousWispr itself is usually in the foreground when the user taps
     * Insert from History, so a live focused field in another app exists only in multi-window mode.
     * Check for a currently focused external editor first; otherwise reuse the last remembered
     * external editor without discarding it, so returning to that app lets the runner insert.
     */
    fun pinTargetForRecovery(insertionPending: Boolean): DictationTargetPin {
        // Refuse if an insertion is pending OR a live dictation already owns the pin, so History
        // recovery can never steal or clear an active dictation's destination.
        if (insertionPending || pinnedTarget != null) return DictationTargetPin.INSERTION_BUSY
        val liveExternal = runCatching {
            findFocusedEditableTarget()?.takeIf {
                OwnFieldAdmission.accepts(service.packageName, it.packageName, it.viewId) &&
                    isInFocusedWindow(it.windowId)
            }
        }.getOrNull()
        val target = if (liveExternal != null) {
            clearTarget()
            lastTarget = liveExternal
            notifyObservedField()
            liveExternal
        } else {
            lastTarget?.takeIf { OwnFieldAdmission.accepts(service.packageName, it.packageName, it.viewId) }
        } ?: return DictationTargetPin.NO_TARGET
        return pinResolvedTarget(target, "recovery")
    }

    private fun pinResolvedTarget(target: TargetSnapshot, role: String): DictationTargetPin {
        pinnedTarget = TargetToken(
            node = AccessibilityNodeInfo.obtain(target.node),
            packageName = target.packageName,
            windowId = target.windowId,
            className = target.className,
            viewId = target.viewId,
        )
        DebugLogger.log(TAG, "Pinned $role editor package=${target.packageName} window=${target.windowId}")
        return DictationTargetPin.PINNED
    }

    /**
     * The remembered editor's key for the bubble's hide-until-the-next-field, or null when nothing is
     * remembered.
     */
    fun rememberedFieldKey(): Any? = lastTarget?.let(::fieldKey)

    /**
     * What the bubble's visibility decision needs to know about the remembered editor (#523): its app and
     * whether it is a password box. Read right after the field was remembered or refreshed. A node that can
     * no longer be read counts as not a password box, so the bubble fails toward showing.
     */
    fun rememberedFieldTraits(): FieldTraits? = lastTarget?.let { target ->
        FieldTraits(
            packageName = target.packageName,
            passwordField = runCatching { PasswordFieldRule.isPasswordField(target.node) }.getOrDefault(false),
        )
    }

    /**
     * The bubble's question (#217): is the remembered editor still focused? When it is not, and the probe is
     * asked to discover, look for the editor that IS focused right now and adopt it. Discovery is one window
     * traversal, so the bubble asks for it only on the rare events.
     *
     * Split in two so its accessibility calls leave the main thread (#561: `findFocus` here froze the phone
     * in an ANR). This half runs on main and only copies the remembered editor, so the refresh off main never
     * touches a node main reads. [FieldProbe.probe] makes every blocking call, on any thread, and reads or
     * writes nothing of this tracker. [FieldAdoption.adopt], back on main, applies the answer only while the
     * remembered editor is still the one copied here: any change in between (a newer focus, a pin, a close)
     * bumps [targetEpoch] and the answer is [FieldProbeOutcome.Stale], so an old answer can never overwrite a
     * newer field. A still-focused editor's refreshed copy replaces the remembered one; a discovered editor
     * replaces it too; a failed discovery keeps it. Either candidate is re-admitted on main first
     * ([fieldAdoptionStep]).
     */
    fun fieldProbe(): FieldProbe {
        val epoch = targetEpoch
        val remembered = lastTarget?.let { it.copy(node = AccessibilityNodeInfo.obtain(it.node)) }
        return FieldProbe { discover, windows ->
            val inSnapshotFocus = { windowId: Int -> windows.any { it.id == windowId && it.isFocused } }
            val stillFocused = remembered != null &&
                runCatching {
                    inSnapshotFocus(remembered.windowId) &&
                        remembered.node.refresh() &&
                        isSafeFocusedEditor(remembered.node) &&
                        isInFocusedWindow(remembered.windowId)
                }.getOrDefault(false)
            // Still focused: the refreshed copy goes to main to replace the remembered node, so the traits read
            // from it (a box toggled to a password box) are as fresh as a refresh on main used to leave them.
            val refreshed = remembered?.takeIf { stillFocused }
            if (!stillFocused) remembered?.node?.recycle()
            var found: TargetSnapshot? = null
            if (!stillFocused && discover) {
                // One fresh window-focus read confirms a discovered editor; the traversal itself used the snapshot.
                found = runCatching {
                    findFocusedEditableTarget({ windows }, inSnapshotFocus)?.let { candidate ->
                        if (isInFocusedWindow(candidate.windowId)) {
                            candidate
                        } else {
                            candidate.node.recycle()
                            null
                        }
                    }
                }.getOrNull()
                // Content-free: counts and booleans only (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
                DebugLogger.debug(TAG, "Bubble discovery found=${found != null} windows=${windows.size}")
            }
            FieldAdoption { adoptFieldProbe(epoch, refreshed ?: found) }
        }
    }

    private fun adoptFieldProbe(epoch: Int, candidate: TargetSnapshot?): FieldProbeOutcome {
        val admitted = candidate?.let { OwnFieldAdmission.accepts(service.packageName, it.packageName, it.viewId) }
        return when (fieldAdoptionStep(epochCurrent = epoch == targetEpoch, candidateAdmitted = admitted)) {
            FieldAdoptionStep.STALE -> {
                candidate?.node?.recycle()
                FieldProbeOutcome.Stale
            }
            FieldAdoptionStep.NO_FIELD -> {
                // A failed discovery, or a candidate withdrawn meanwhile, keeps the remembered editor.
                candidate?.node?.recycle()
                FieldProbeOutcome.Current(null)
            }
            FieldAdoptionStep.ADOPT -> {
                val adopted = checkNotNull(candidate)
                clearTarget()
                lastTarget = adopted
                notifyObservedField()
                FieldProbeOutcome.Current(fieldKey(adopted))
            }
        }
    }

    /**
     * Runs [block] against the pinned editor if one is pinned and present right now, else returns null.
     *
     * Uses the exact copied node captured before dictation. Metadata alone is ambiguous for Compose
     * editors where several fields can have no view ID. Samsung can invalidate that copied object
     * while a temporary Activity is above the editor; in that case reacquire only a focused node whose
     * framework identity is equal to the originally pinned node.
     */
    fun <T> withPinnedNode(requireWindowFocus: Boolean = false, block: (AccessibilityNodeInfo) -> T): T? {
        val expected = pinnedTarget ?: return null
        if (requireWindowFocus && !isInFocusedWindow(expected.windowId)) return null
        val pinnedWindowRoot = findPinnedWindowRoot(expected) ?: return null
        var reacquiredNode: AccessibilityNodeInfo? = null
        try {
            val originalRefreshed = expected.node.refresh()
            val originalReady = originalRefreshed &&
                expected.node.isVisibleToUser && expected.node.isEditable &&
                expected.node.isFocused && matchesPinnedTarget(expected.node, expected)
            val node = if (originalReady) {
                expected.node
            } else {
                pinnedWindowRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.also {
                    reacquiredNode = it
                } ?: return null
            }
            if (node.isVisibleToUser && node.isEditable && node.isFocused &&
                matchesPinnedTarget(node, expected) &&
                (node === expected.node || node == expected.node)
            ) {
                if (node !== expected.node) {
                    DebugLogger.debug(TAG, "Reacquired the pinned editor after its node became stale")
                }
                return block(node)
            }
            return null
        } finally {
            reacquiredNode?.recycle()
            pinnedWindowRoot.recycle()
        }
    }

    /**
     * Gives input focus back to the pinned editor, ONCE, when a SIBLING field in the same focused window
     * holds it (#201). Every guard must pass before `ACTION_FOCUS` is sent, and any guard that does not
     * (or throws) answers [RefocusResult.DECLINED] having sent nothing:
     * - the pin exists, its window is found and HAS input focus (`findPinnedWindowRoot` alone finds an
     *   unfocused window, so the focus check is here; another app on top is never touched);
     * - the pinned node refreshes, is visible, editable and focusable, matches the pin, is not already
     *   focused, and advertises `ACTION_FOCUS`;
     * - the window's input-focused node is a DIFFERENT editable node (a sibling holds focus, not "nothing").
     * Never changes text and never moves focus across windows.
     */
    fun refocusPinnedNode(): RefocusResult {
        val expected = pinnedTarget ?: return RefocusResult.DECLINED
        val pinnedNode = expected.node
        var windowRoot: AccessibilityNodeInfo? = null
        var sibling: AccessibilityNodeInfo? = null
        try {
            val refusal = RefocusGuards.firstRefusal(
                windowFocused = { isInFocusedWindow(expected.windowId) },
                windowFound = {
                    windowRoot = findPinnedWindowRoot(expected)
                    windowRoot != null
                },
                pinRefreshed = { pinnedNode.refresh() },
                pinUsable = {
                    pinnedNode.isVisibleToUser && pinnedNode.isEditable && pinnedNode.isFocusable &&
                        !pinnedNode.isFocused && matchesPinnedTarget(pinnedNode, expected)
                },
                advertisesFocus = { pinnedNode.actionList.any { it.id == AccessibilityNodeInfo.ACTION_FOCUS } },
                siblingHoldsFocus = {
                    sibling = windowRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    sibling?.let { it.isEditable && it != pinnedNode } == true
                },
            )
            if (refusal != null) return RefocusResult.DECLINED
        } catch (error: Exception) {
            return RefocusResult.DECLINED
        } finally {
            sibling?.recycle()
            windowRoot?.recycle()
        }
        // The one focus action. A throw here may have moved focus, so it is its own result.
        return try {
            if (pinnedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) RefocusResult.ACCEPTED else RefocusResult.REJECTED
        } catch (error: Exception) {
            RefocusResult.THREW
        }
    }

    fun clearTarget() {
        lastTarget?.node?.recycle()
        lastTarget = null
    }

    private fun notifyObservedField() {
        onFieldObserved(rememberedFieldTraits())
    }

    fun clearPinnedTarget() {
        pinnedTarget?.node?.recycle()
        pinnedTarget = null
    }

    /** Releases both node copies: the pin, then the remembered editor. Safe to call twice. */
    fun close() {
        clearPinnedTarget()
        clearTarget()
        notifyObservedField()
    }

    /** The pin's search: the live window list and a live focus read for each question, as before #561. */
    private fun findFocusedEditableTarget(): TargetSnapshot? = findFocusedEditableTarget({ service.windows }, ::isInFocusedWindow)

    /**
     * The search for the editor focused in the window that holds input focus. [windows] and [inFocusedWindow]
     * are the window list and the window-focus question: live reads for the pin, the check's one snapshot for
     * the bubble's probe.
     */
    private fun findFocusedEditableTarget(
        windows: () -> List<AccessibilityWindowInfo>,
        inFocusedWindow: (Int) -> Boolean,
    ): TargetSnapshot? {
        // Only the window that currently holds input focus may supply the pin. An editor in a background
        // window keeps its own focus flag, so an unfiltered search could rediscover a stale editor in an
        // unfocused window and pin it, sending the words to the app the user just left (Codex review,
        // BUG 1 fast-follow, 2026-09-13). The filter is applied DURING the search, not after, so a match
        // in the focused window is never overlooked because an unfocused one answered first.
        val activeRoot = service.rootInActiveWindow
        activeRoot?.takeIf { inFocusedWindow(it.windowId) }?.let { root ->
            findFocusedEditableTarget(root)?.let { target ->
                activeRoot.recycle()
                return target
            }
        }
        val activeWindowId = activeRoot?.windowId
        activeRoot?.recycle()

        for (window in windows()) {
            if (!window.isFocused) continue
            if (window.id == activeWindowId) continue
            val root = window.root ?: continue
            val target = findFocusedEditableTarget(root)
            root.recycle()
            if (target != null) return target
        }
        return null
    }

    private fun findFocusedEditableTarget(root: AccessibilityNodeInfo?): TargetSnapshot? {
        root ?: return null
        val rootPackage = root.packageName?.toString().orEmpty()
        if (!OwnFieldAdmission.searches(service.packageName, rootPackage)) return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return try {
            val focusedPackage = focused.packageName?.toString().orEmpty()
            if (!isSafeFocusedEditor(focused) ||
                focusedPackage != rootPackage ||
                focused.windowId != root.windowId
            ) {
                null
            } else {
                TargetSnapshot(
                    node = AccessibilityNodeInfo.obtain(focused),
                    packageName = focusedPackage,
                    windowId = focused.windowId,
                    className = focused.className?.toString().orEmpty(),
                    viewId = focused.viewIdResourceName,
                    capturedAtMs = SystemClock.elapsedRealtime(),
                )
            }
        } finally {
            focused.recycle()
        }
    }

    private fun isSafeFocusedEditor(node: AccessibilityNodeInfo): Boolean =
        node.isEditable && node.isFocused && node.isVisibleToUser &&
            OwnFieldAdmission.accepts(service.packageName, node.packageName?.toString(), node.viewIdResourceName)

    /**
     * Does the window holding the editor have input focus right now? In split screen an editor in
     * the other pane keeps reporting itself focused after the user moves to this pane, so the node's
     * own focus flag alone would keep the bubble offering a field the user has left (Codex review of
     * the Play branch, round 5). The bubble asks the window, and hides until focus returns to it.
     */
    private fun isInFocusedWindow(windowId: Int): Boolean = runCatching {
        service.windows.any { it.id == windowId && it.isFocused }
    }.getOrDefault(false)

    /**
     * The editor's identity for hide-until-the-next-field: window id plus the node's own hash, which
     * the framework derives from its source node id. Never the window or the view id alone, so two
     * editors in one window are two keys.
     */
    private fun fieldKey(target: TargetSnapshot): Any = FieldKey(target.windowId, target.node.hashCode())

    private fun findPinnedWindowRoot(token: TargetToken): AccessibilityNodeInfo? {
        val activeRoot = service.rootInActiveWindow
        if (matchesPinnedWindow(activeRoot, token)) return activeRoot
        val activeWindowId = activeRoot?.windowId
        activeRoot?.recycle()

        for (window in service.windows) {
            if (window.id == activeWindowId) continue
            if (window.id != token.windowId) continue
            val root = window.root ?: continue
            if (matchesPinnedWindow(root, token)) return root
            root.recycle()
        }
        return null
    }

    private fun matchesPinnedWindow(root: AccessibilityNodeInfo?, token: TargetToken): Boolean =
        root != null && AccessibilityInsertionRules.isExpectedWindow(
            packageName = root.packageName?.toString(),
            windowId = root.windowId,
            expectedPackageName = token.packageName,
            expectedWindowId = token.windowId,
        )

    private fun matchesPinnedTarget(node: AccessibilityNodeInfo, token: TargetToken): Boolean {
        if (node.packageName?.toString() != token.packageName) return false
        if (node.windowId != token.windowId) return false
        if (node.className?.toString().orEmpty() != token.className) return false
        return token.viewId == null || node.viewIdResourceName == token.viewId
    }
}
