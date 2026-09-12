package com.envi.wispr.shortcuts

import java.util.UUID

/**
 * A request the floating bubble made: which process it came from and where it sits in that process's
 * order. Carried as one string on the launcher and service intents.
 */
data class BubbleRequestToken(val epoch: String, val seq: Long) {
    fun encode(): String = "$epoch:$seq"

    companion object {
        fun parse(value: String?): BubbleRequestToken? {
            if (value.isNullOrBlank()) return null
            val at = value.lastIndexOf(':')
            if (at <= 0) return null
            val seq = value.substring(at + 1).toLongOrNull() ?: return null
            if (seq <= 0) return null
            return BubbleRequestToken(value.substring(0, at), seq)
        }
    }
}

/**
 * The ledger that orders bubble requests, so a release can never be lost onto a later take and a late
 * start can never begin a recording nobody is holding.
 *
 * The bubble starts a dictation through a transparent launcher activity, so its START arrives at the
 * session owner asynchronously, while its STOP and CANCEL go straight to the owner. Between two takes
 * the owner service is destroyed and recreated. Every earlier design that waited for an acknowledgement
 * in the bubble had an ordering hole in that gap (issue #135 plan, review rounds 2 to 5). This object is
 * process-local, so it survives the service and dies only with the process, where the epoch changes and
 * every older token is stale by construction.
 *
 * Three facts, all resolved inside the owner's single-threaded command handler:
 * - the high-water mark: the highest `seq` admitted or retired; a START at or below it is stale;
 * - one early note: the highest `seq` above the mark whose STOP or CANCEL arrived before its START;
 *   a `seq` it displaces or declines is retired first, so its START is refused rather than admitted
 *   without its release; for the same `seq`, cancelled wins;
 * - the admitted take's `seq`, matched BEFORE the mark is consulted, because admission set the mark to it.
 */
open class BubbleRequestLedger internal constructor(private val epoch: String) {

    sealed class StartDecision {
        /** Another process's token, or a `seq` at or below the high-water mark. Nothing changes. */
        object Stale : StartDecision()

        /** Above the mark but the owner is busy. The `seq` is retired so a later release for it is ignored. */
        object RefusedBusy : StartDecision()

        /** Admitted, with whatever early note this `seq` had already earned. */
        data class Admitted(val stopAfterRecording: Boolean, val cancelAtOnce: Boolean) : StartDecision()
    }

    sealed class CommandDecision {
        /** Another process's token. Nothing changes. */
        object Rejected : CommandDecision()

        /** The admitted take's own token: apply the command to the live take. */
        object ApplyToAdmitted : CommandDecision()

        /** Recorded for a START that has not arrived yet. */
        object Noted : CommandDecision()

        /** At or below the mark, or declined by a higher note: retired, nothing to apply. */
        object Ignored : CommandDecision()
    }

    private data class Note(val seq: Long, val cancelled: Boolean)

    private val lock = Any()
    private var counter = 0L
    private var highWater = 0L
    private var note: Note? = null

    /** A fresh token for a new bubble request. */
    fun mint(): BubbleRequestToken = synchronized(lock) {
        counter += 1
        BubbleRequestToken(epoch, counter)
    }

    /** A START with [token] arrived; [ownerIdle] is the owner's state at that moment. */
    fun resolveStart(token: BubbleRequestToken, ownerIdle: Boolean): StartDecision = synchronized(lock) {
        if (token.epoch != epoch) return StartDecision.Stale
        if (token.seq <= highWater) return StartDecision.Stale
        highWater = token.seq
        val earned = note?.takeIf { it.seq == token.seq }
        // Any note at or below this seq is spent: consumed if it is this seq, dropped if lower (its own
        // START is now stale by the mark).
        if (note != null && note!!.seq <= token.seq) note = null
        if (!ownerIdle) return StartDecision.RefusedBusy
        StartDecision.Admitted(
            stopAfterRecording = earned != null && !earned.cancelled,
            cancelAtOnce = earned != null && earned.cancelled,
        )
    }

    /**
     * A STOP ([cancel] false) or CANCEL ([cancel] true) with [token] arrived; [admittedSeq] is the live
     * take's `seq` when the live take is a bubble request, else null.
     */
    fun resolveCommand(token: BubbleRequestToken, cancel: Boolean, admittedSeq: Long?): CommandDecision = synchronized(lock) {
        if (token.epoch != epoch) return CommandDecision.Rejected
        if (admittedSeq != null && token.seq == admittedSeq) return CommandDecision.ApplyToAdmitted
        if (token.seq <= highWater) return CommandDecision.Ignored
        val held = note
        when {
            held == null -> note = Note(token.seq, cancel)
            held.seq == token.seq -> note = Note(token.seq, held.cancelled || cancel)
            held.seq < token.seq -> {
                // The lower note is displaced: retire it so its START cannot be admitted unreleased.
                highWater = held.seq
                note = Note(token.seq, cancel)
            }
            else -> {
                // A lower seq than the note held: retire this one and keep the higher note.
                highWater = token.seq
                return CommandDecision.Ignored
            }
        }
        CommandDecision.Noted
    }
}

/** The app's one ledger, shared by the overlay (mints) and the session owner (resolves). */
object BubbleRequests : BubbleRequestLedger(UUID.randomUUID().toString().substring(0, 8))
