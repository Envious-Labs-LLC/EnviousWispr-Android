package com.envi.wispr.paste

/**
 * The one field of EnviousWispr's own that the accessibility service may treat as a dictation target.
 *
 * The service ignores its own package everywhere else: the settings pages, the dictionary, History,
 * are never a place the floating button should appear or words should be pasted. The onboarding
 * practice box is the exception, and it is the exception on purpose: the first dictation a new user
 * makes runs the same path a Gmail field runs, bubble, pin, capture, insertion, so a "Nice, that
 * worked!" proves the real thing. The screen admits its field while it is on screen and withdraws it
 * when it leaves; nothing else in the app is admitted, ever.
 *
 * Process-local, like the overlay state bridge: the service, the session owner and the screen share
 * one process (`PasteServiceProcessManifestTest`).
 */
object OwnFieldAdmission {
    @Volatile
    private var admittedViewId: String? = null

    /** The admitted field's accessibility view id, or null when nothing of our own may be a target. */
    val admitted: String? get() = admittedViewId

    fun admit(viewId: String) {
        admittedViewId = viewId
    }

    /** Withdraws [viewId] if it is the admitted one; a later admission by another screen is untouched. */
    fun withdraw(viewId: String) {
        if (admittedViewId == viewId) admittedViewId = null
    }

    /**
     * Whether a node in [packageName] carrying [viewId] may be a target for the service whose own
     * package is [self]. Another app's node: always. Our own: only the admitted field, by id.
     */
    fun accepts(self: String, packageName: String?, viewId: String?): Boolean {
        val pkg = packageName.orEmpty()
        if (pkg.isBlank()) return false
        if (pkg != self) return true
        val admitted = admittedViewId ?: return false
        return viewId == admitted
    }

    /** Whether a window whose root is in [packageName] is worth searching for a target at all. */
    fun searches(self: String, packageName: String?): Boolean {
        val pkg = packageName.orEmpty()
        if (pkg.isBlank()) return false
        return pkg != self || admittedViewId != null
    }
}
