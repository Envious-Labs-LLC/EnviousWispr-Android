package com.envi.wispr.asr

/** Attempt every required native closer; an incomplete close cannot look like successful retirement. */
internal fun closeAsrNative(resources: Iterable<AutoCloseable>) {
    var failure: AsrCleanupFailure? = null
    resources.forEach { resource ->
        try { resource.close() } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = if (error is AsrCleanupFailure) error else AsrCleanupFailure(error)
            else if (error !== previous) previous.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

/** As with use, but cleanup failure has a distinct outcome that the native owner must poison. */
internal inline fun <T : AutoCloseable, R> T.useAsrNative(block: (T) -> R): R {
    var original: Throwable? = null
    try { return block(this) } catch (failure: Throwable) { original = failure; throw failure }
    finally {
        try { close() } catch (failure: Throwable) {
            val cleanup = if (failure is AsrCleanupFailure) failure else AsrCleanupFailure(failure)
            original?.let { if (it !== cleanup) cleanup.addSuppressed(it) }
            throw cleanup
        }
    }
}
