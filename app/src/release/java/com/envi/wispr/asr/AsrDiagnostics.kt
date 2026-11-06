package com.envi.wispr.asr

/** Release has no observer receiver, client helper, listener, event store, worker or serialized diagnostics. */
internal object AsrDiagnostics {
    fun registered(generation: Long, request: Long, take: String, callback: Int) { }
    fun active(generation: Long, request: Long, take: String, callback: Int) { }
    fun callbackDied(generation: Long, request: Long, take: String, callback: Int) { }
    fun retired(generation: Long, request: Long, take: String, callback: Int, outcome: AsrRequest.Retirement, cancelled: Boolean) { }
    fun modelOpening(generation: Long) { }
    fun ownerRetired(generation: Long) { }
    fun observer(generation: Long, request: Long, take: String, callback: Int): AsrObserver = AsrObserver.NONE
}
