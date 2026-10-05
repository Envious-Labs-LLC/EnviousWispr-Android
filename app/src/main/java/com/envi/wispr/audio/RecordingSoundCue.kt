package com.envi.wispr.audio

/** One take's sound decision, owned by its coordinator. Failed starts never arm an unmatched stop. */
internal class RecordingSoundCue(private val play: (RecordingSoundPairing, RecordingSoundMoment) -> Boolean) {
    private sealed interface State {
        data object Waiting : State
        data class Started(val pairing: RecordingSoundPairing) : State
        data object Closed : State
    }
    private var state: State = State.Waiting

    fun live(enabled: Boolean, pairing: RecordingSoundPairing) {
        if (state != State.Waiting) return
        state = State.Closed
        if (enabled && runCatching { play(pairing, RecordingSoundMoment.START) }.getOrDefault(false)) {
            state = State.Started(pairing)
        }
    }

    fun captureClosed(resourcesClosed: Boolean) {
        val started = state as? State.Started
        state = State.Closed
        if (started != null && resourcesClosed) runCatching { play(started.pairing, RecordingSoundMoment.STOP) }
    }

    fun abandon() { state = State.Closed }
}
