package com.envi.wispr.polish

/** A successful native construction and the candidates that failed before it, captured together. */
internal data class S1BackendLoad<T>(val runtime: T, val backend: String, val failedCodes: String)
internal fun <T> loadFirstS1Backend(units: List<String>, create: (String) -> T): S1BackendLoad<T> {
    val failed = mutableListOf<String>()
    var lastFailure: Throwable? = null
    for (unit in units) {
        try { return S1BackendLoad(create(unit), unit, failed.joinToString(",")) }
        catch (error: Throwable) { failed += unit; lastFailure = error }
    }
    throw S1BackendLoadException(failed.joinToString(","), lastFailure)
}
internal class S1BackendLoadException(val failedCodes: String, cause: Throwable?) : IllegalStateException("No S1 compute backend could load", cause)
