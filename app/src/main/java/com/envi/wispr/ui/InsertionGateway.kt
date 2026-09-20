package com.envi.wispr.ui

import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.paste.DictationTargetPin
import com.envi.wispr.paste.InsertionHandoff
import com.envi.wispr.paste.PasteAccessibilityService

/**
 * The five doors the session owner uses into the accessibility service (#186): the members of
 * [PasteAccessibilityService]'s companion it calls, and nothing else. A JVM test fakes this.
 */
internal interface InsertionGateway {
    fun pinTargetForDictation(): DictationTargetPin
    fun pinnedFieldId(): String?
    fun releasePinnedTarget()
    fun pasteWhenTargetReturns(transcriptId: Long, text: String, policy: ClipboardInsertionPolicy, takeId: String): InsertionHandoff

    /** `PasteAccessibilityService.isBound.value`: liveness, never the setting string. */
    fun isBound(): Boolean
}

/** Production: every call delegates to the [PasteAccessibilityService] companion. */
internal object AccessibilityInsertionGateway : InsertionGateway {
    override fun pinTargetForDictation(): DictationTargetPin = PasteAccessibilityService.pinTargetForDictation()
    override fun pinnedFieldId(): String? = PasteAccessibilityService.pinnedFieldId()
    override fun releasePinnedTarget() = PasteAccessibilityService.releasePinnedTarget()
    override fun pasteWhenTargetReturns(transcriptId: Long, text: String, policy: ClipboardInsertionPolicy, takeId: String): InsertionHandoff =
        PasteAccessibilityService.pasteWhenTargetReturns(transcriptId, text, policy = policy, takeId = takeId)
    override fun isBound(): Boolean = PasteAccessibilityService.isBound.value
}
