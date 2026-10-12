package com.envi.wispr.feedback

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One atomic document commits the report, admission high-water mark and draft clearing together. IO callers only. */
internal class FeedbackStore(
    private val file: File,
    private val write: (File, ByteArray) -> Unit = ::atomicFeedbackWrite,
) {
    companion object {
        const val MAX_REPORTS = 50
        const val MAX_BYTES = 2 * 1024 * 1024
        // All feedback workers and UI use main process. Every store instance shares this document lock.
        private val lock = Any()
    }
    enum class Admission { SAVED, ALREADY_SAVED, STALE, FULL }
    fun read(): FeedbackDocument = synchronized(lock) { load() }
    fun saveDraft(draft: FeedbackDraft) = synchronized(lock) {
        val doc = load()
        if (draft.revision > doc.admittedRevision && draft.revision > doc.draft.revision) commit(doc.copy(draft = draft))
    }
    fun admit(draft: FeedbackDraft, report: FeedbackRecord): Admission = synchronized(lock) {
        val doc = load()
        if (draft.revision == doc.admittedRevision) return@synchronized Admission.ALREADY_SAVED
        if (draft.revision < doc.admittedRevision) return@synchronized Admission.STALE
        if (doc.records.size >= MAX_REPORTS) return@synchronized Admission.FULL
        // A newer edit is never cleared, even if it reached storage while this report was being built.
        val remaining = if (doc.draft.revision <= draft.revision) FeedbackDraft(draft.revision) else doc.draft
        val next = doc.copy(draft = remaining, admittedRevision = draft.revision, admittedId = report.id, records = doc.records + report)
        if (next.json().toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) return@synchronized Admission.FULL
        commit(next)
        Admission.SAVED
    }
    fun settle(id: String, reply: FeedbackReply, launch: String) = synchronized(lock) {
        val doc = load()
        val records = when (reply.kind) {
            FeedbackReply.Kind.ACCEPTED -> doc.records.filterNot { it.id == id }
            FeedbackReply.Kind.REJECTED -> doc.records.map { if (it.id == id) it.copy(state = FeedbackRecordState.REJECTED) else it }
            FeedbackReply.Kind.CONFIGURATION -> doc.records.map { if (it.id == id) it.copy(state = FeedbackRecordState.CONFIGURATION) else it }
            FeedbackReply.Kind.RETRY -> doc.records
        }
        commit(doc.copy(records = records, holdUntilMs = maxOf(doc.holdUntilMs, reply.holdUntilMs),
            pausedLaunch = if (reply.kind == FeedbackReply.Kind.CONFIGURATION) launch else doc.pausedLaunch))
    }
    fun recover(launch: String): FeedbackDocument = synchronized(lock) {
        val doc = load()
        if (doc.pausedLaunch != null && doc.pausedLaunch != launch) {
            val next = doc.copy(pausedLaunch = null, records = doc.records.map {
                if (it.state == FeedbackRecordState.CONFIGURATION) it.copy(state = FeedbackRecordState.PENDING) else it
            })
            commit(next)
            next
        } else doc
    }
    private fun load(): FeedbackDocument {
        if (!file.exists()) return FeedbackDocument()
        require(file.length() <= MAX_BYTES) { "Feedback storage exceeds limit" }
        return FeedbackDocument.read(JSONObject(file.readText(Charsets.UTF_8)))
    }
    private fun commit(doc: FeedbackDocument) {
        val bytes = doc.json().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Feedback storage is full" }
        write(file, bytes)
    }
}

private fun atomicFeedbackWrite(file: File, bytes: ByteArray) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val temp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
