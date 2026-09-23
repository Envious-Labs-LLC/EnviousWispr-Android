package com.envi.wispr.paste

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.envi.wispr.debug.DebugLogger

/**
 * Which editor a dictation aims at (#217): the editor the user last focused (remembered), and the editor a
 * take pinned (the one its words go to). Both the pin and the floating bubble ask this one owner the same
 * question, which editor is focused in the window that holds input focus, so they cannot disagree.
 *
 * Every call runs on the service's main thread. The node copies this holds are its own: it recycles them,
 * and a caller reads the pinned editor only through [withPinnedNode] and the three read-only properties.
 */
internal class EditorTargetTracker(private val service: AccessibilityService) {

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

    private var lastTarget: TargetSnapshot? = null
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
        }
        target ?: return DictationTargetPin.NO_TARGET
        pinnedTarget = TargetToken(
            node = AccessibilityNodeInfo.obtain(target.node),
            packageName = target.packageName,
            windowId = target.windowId,
            className = target.className,
            viewId = target.viewId,
        )
        DebugLogger.log(TAG, "Pinned original editor package=${target.packageName} window=${target.windowId}")
        return DictationTargetPin.PINNED
    }

    /**
     * The remembered editor's key for the bubble's hide-until-the-next-field, or null when nothing is
     * remembered.
     */
    fun rememberedFieldKey(): Any? = lastTarget?.let(::fieldKey)

    /**
     * The bubble's question (#217): is the remembered editor still focused? When it is not, and [discover]
     * is set, look for the editor that IS focused right now and adopt it. Discovery is one window
     * traversal, so the bubble asks for it only on the rare events. The old target is recycled only when a
     * new one was found; a failed discovery keeps it. Returns the field's key, or null when no editor is
     * focused.
     */
    fun refreshFocusedField(discover: Boolean): Any? {
        var target = lastTarget
        var stillFocused = target != null &&
            runCatching { target.node.refresh() && isSafeFocusedEditor(target.node) && isInFocusedWindow(target.windowId) }
                .getOrDefault(false)
        if (!stillFocused && discover) {
            val found = runCatching { findFocusedEditableTarget()?.takeIf { isInFocusedWindow(it.windowId) } }.getOrNull()
            // Content-free: counts and booleans only (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
            DebugLogger.debug(
                TAG,
                "Bubble discovery found=${found != null} windows=${runCatching { service.windows.size }.getOrDefault(-1)}",
            )
            if (found != null) {
                clearTarget()
                lastTarget = found
                target = found
                stillFocused = true
            }
        }
        return if (stillFocused) fieldKey(target!!) else null
    }

    /**
     * Runs [block] against the pinned editor if one is pinned and present right now, else returns null.
     *
     * Uses the exact copied node captured before dictation. Metadata alone is ambiguous for Compose
     * editors where several fields can have no view ID. Samsung can invalidate that copied object
     * while a temporary Activity is above the editor; in that case reacquire only a focused node whose
     * framework identity is equal to the originally pinned node.
     */
    fun <T> withPinnedNode(block: (AccessibilityNodeInfo) -> T): T? {
        val expected = pinnedTarget ?: return null
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

    fun clearTarget() {
        lastTarget?.node?.recycle()
        lastTarget = null
    }

    fun clearPinnedTarget() {
        pinnedTarget?.node?.recycle()
        pinnedTarget = null
    }

    /** Releases both node copies: the pin, then the remembered editor. Safe to call twice. */
    fun close() {
        clearPinnedTarget()
        clearTarget()
    }

    private fun findFocusedEditableTarget(): TargetSnapshot? {
        // Only the window that currently holds input focus may supply the pin. An editor in a background
        // window keeps its own focus flag, so an unfiltered search could rediscover a stale editor in an
        // unfocused window and pin it, sending the words to the app the user just left (Codex review,
        // BUG 1 fast-follow, 2026-09-13). The filter is applied DURING the search, not after, so a match
        // in the focused window is never overlooked because an unfocused one answered first.
        val activeRoot = service.rootInActiveWindow
        activeRoot?.takeIf { isInFocusedWindow(it.windowId) }?.let { root ->
            findFocusedEditableTarget(root)?.let { target ->
                activeRoot.recycle()
                return target
            }
        }
        val activeWindowId = activeRoot?.windowId
        activeRoot?.recycle()

        for (window in service.windows) {
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
