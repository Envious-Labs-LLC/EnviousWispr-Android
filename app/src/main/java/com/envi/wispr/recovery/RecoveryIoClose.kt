package com.envi.wispr.recovery

/** A failed native close overrides a body failure; it must never hide as suppressed vendor data. */
internal object RecoveryIoClose {
    fun <T : AutoCloseable, R> using(resource: T, action: (T) -> R): R = try {
        try {
            action(resource)
        } catch (failure: RecoveryStoreException) {
            throw RecoveryStoreException(failure.reason)
        }
    } finally {
        try {
            resource.close()
        } catch (_: Throwable) {
            throw RecoveryStoreException(RecoveryStoreFailure.IO_UNAVAILABLE)
        }
    }
}
