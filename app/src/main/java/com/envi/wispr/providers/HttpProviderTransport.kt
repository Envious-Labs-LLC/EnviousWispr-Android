package com.envi.wispr.providers

import com.envi.wispr.debug.DebugLogger

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The one diagnostic tag for the polish client, the discovery client and the transport (#189). The value
 * predates the split and is kept so every `Key check:`, `Discovery:` and `Cloud request failed:` line reads
 * exactly as before; nothing greps it.
 */
internal const val PROVIDER_LOG_TAG = "ProviderPolishClient"

internal data class RequestPlan(
    val url: URI,
    val headers: Map<String, String>,
    /** Null sends no body and no Content-Type: the key check's GET. */
    val body: String?,
    val responseFormat: ProviderReplyFormat,
    val method: String = "POST",
) {
    override fun toString(): String =
        "RequestPlan(url=<redacted>, headers=<redacted>, body=<redacted>, responseFormat=$responseFormat)"
}

/** What one connection produced, before any parsing: the provider's status with its body, or the failure that stopped the read. */
internal sealed interface Transport {
    data class Response(val status: Int, val body: String) : Transport
    data class Failed(val kind: ProviderFailureKind, val status: Int? = null) : Transport
}

/** One HTTP round trip under a deadline, with a cancel hook. [HttpProviderTransport] is the production implementation. */
internal interface ProviderTransport {
    fun run(
        plan: RequestPlan,
        cancellation: ProviderCancellation,
        overallTimeoutMs: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Transport
}

/** Deadline arithmetic shared by both clients and the transport: nanos in, whole milliseconds out, never below zero. */
internal object ProviderDeadlines {
    fun remainingMillis(deadline: Long): Int {
        val nanos = deadline - System.nanoTime()
        return if (nanos <= 0) 0 else minOf(Int.MAX_VALUE.toLong(), TimeUnit.NANOSECONDS.toMillis(nanos).coerceAtLeast(1)).toInt()
    }

    fun minTimeout(configured: Int, remaining: Int): Int = minOf(configured, remaining).coerceAtLeast(1)
}

/**
 * `HttpURLConnection` execution and cancellation, moved whole out of `ProviderPolishClient` (#189): the
 * request executor, the overall deadline, the cancel hook, the redirect refusal, the request and response
 * size caps, and every catch that decides CANCELLED against TIMEOUT. It logs the SHAPE of a failure (the
 * exception class) and never a body, a header or a key.
 */
internal class HttpProviderTransport(
    private val logWarn: (String) -> Unit = { DebugLogger.warn(PROVIDER_LOG_TAG, it) },
) : ProviderTransport {
    /** The executor, the overall deadline and the cancel hook, shared by the polish request and the key check. */
    override fun run(
        plan: RequestPlan,
        cancellation: ProviderCancellation,
        overallTimeoutMs: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Transport {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(overallTimeoutMs.toLong())
        val activeConnection = ActiveConnection()
        val future: Future<Transport> = REQUEST_EXECUTOR.submit<Transport> {
            executeRequest(plan, cancellation, deadline, activeConnection, connectTimeoutMs, readTimeoutMs)
        }
        val cancelRegistration = cancellation.onCancel {
            activeConnection.disconnect()
            future.cancel(true)
        }
        return try {
            future.get(overallTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            activeConnection.disconnect()
            future.cancel(true)
            Transport.Failed(ProviderFailureKind.TIMEOUT)
        } catch (_: CancellationException) {
            if (cancellation.isCancelled) Transport.Failed(ProviderFailureKind.CANCELLED)
            else Transport.Failed(ProviderFailureKind.TIMEOUT)
        } catch (_: InterruptedException) {
            activeConnection.disconnect()
            future.cancel(true)
            Thread.currentThread().interrupt()
            Transport.Failed(ProviderFailureKind.CANCELLED)
        } catch (failure: ExecutionException) {
            logWarn("Cloud request failed: ${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}")
            if (cancellation.isCancelled) Transport.Failed(ProviderFailureKind.CANCELLED)
            else Transport.Failed(ProviderFailureKind.NETWORK)
        } finally {
            cancelRegistration.close()
            activeConnection.disconnect()
        }
    }

    /**
     * One connection: the status the provider sent with its body, or the failure that stopped the read.
     * A read failure on an error status keeps that status beside its kind, so a caller can never mistake
     * an unread 401 for a verdict (#61).
     */
    private fun executeRequest(
        plan: RequestPlan,
        cancellation: ProviderCancellation,
        deadline: Long,
        activeConnection: ActiveConnection,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Transport {
        var connection: HttpURLConnection? = null
        // The status the provider already sent, kept beside any failure that follows it (#61).
        var observedStatus: Int? = null
        return try {
            ensureActive(cancellation, deadline)
            val remainingMs = ProviderDeadlines.remainingMillis(deadline)
            if (remainingMs <= 0) {
                Transport.Failed(ProviderFailureKind.TIMEOUT)
            } else {
                connection = (plan.url.toURL().openConnection() as? HttpURLConnection)
                    ?: return Transport.Failed(ProviderFailureKind.NETWORK)
                activeConnection.set(connection)
                val body = plan.body?.toByteArray(StandardCharsets.UTF_8)
                connection.apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    requestMethod = plan.method
                    doOutput = body != null
                    connectTimeout = ProviderDeadlines.minTimeout(connectTimeoutMs, remainingMs)
                    readTimeout = ProviderDeadlines.minTimeout(readTimeoutMs, remainingMs)
                    if (body != null) setRequestProperty("Content-Type", "application/json")
                    plan.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                }
                if (body != null && body.size > MAX_REQUEST_BYTES) {
                    Transport.Failed(ProviderFailureKind.INVALID_CONFIGURATION)
                } else {
                    if (body != null) connection.outputStream.use { it.write(body) }
                    ensureActive(cancellation, deadline)
                    val status = connection.responseCode.also { observedStatus = it }
                    if (status in 300..399) {
                        Transport.Failed(ProviderFailureKind.REDIRECT_REJECTED, status)
                    } else {
                        when (val response = readResponse(connection, status, cancellation, deadline)) {
                            is ResponseRead.Failure -> Transport.Failed(response.kind, status)
                            is ResponseRead.Success -> Transport.Response(status, response.body)
                        }
                    }
                }
            }
        } catch (_: ProviderCancelledException) {
            Transport.Failed(ProviderFailureKind.CANCELLED, observedStatus)
        } catch (_: ProviderTimedOutException) {
            Transport.Failed(ProviderFailureKind.TIMEOUT, observedStatus)
        } catch (_: SocketTimeoutException) {
            Transport.Failed(if (cancellation.isCancelled) ProviderFailureKind.CANCELLED else ProviderFailureKind.TIMEOUT, observedStatus)
        } catch (failure: IOException) {
            // Shape only, never content: the exception class names the layer that refused (#77).
            logWarn("Cloud request failed: ${failure.javaClass.simpleName}")
            Transport.Failed(failureKindAfter(cancellation, deadline), observedStatus)
        } catch (failure: RuntimeException) {
            logWarn("Cloud request failed: ${failure.javaClass.simpleName}")
            Transport.Failed(failureKindAfter(cancellation, deadline), observedStatus)
        } finally {
            activeConnection.clear(connection)
            connection?.disconnect()
        }
    }

    private fun failureKindAfter(cancellation: ProviderCancellation, deadline: Long): ProviderFailureKind = when {
        cancellation.isCancelled -> ProviderFailureKind.CANCELLED
        System.nanoTime() >= deadline -> ProviderFailureKind.TIMEOUT
        else -> ProviderFailureKind.NETWORK
    }

    private fun readResponse(
        connection: HttpURLConnection,
        status: Int,
        cancellation: ProviderCancellation,
        deadline: Long,
    ): ResponseRead {
        val declared = connection.getHeaderFieldLong("Content-Length", -1L)
        if (declared > MAX_RESPONSE_BYTES) return ResponseRead.Failure(ProviderFailureKind.RESPONSE_TOO_LARGE)
        // An error response with no body has a null error stream on Android; that is an EMPTY body, not a
        // network failure (#79): the status still names what happened.
        val stream = if (status >= 400) {
            connection.errorStream ?: return ResponseRead.Success("")
        } else {
            connection.inputStream ?: return ResponseRead.Failure(ProviderFailureKind.NETWORK)
        }
        stream.use { input ->
            val output = ByteArrayOutputStream(minOf(MAX_RESPONSE_BYTES, 16 * 1024))
            val buffer = ByteArray(4096)
            while (true) {
                ensureActive(cancellation, deadline)
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_RESPONSE_BYTES) {
                    return ResponseRead.Failure(ProviderFailureKind.RESPONSE_TOO_LARGE)
                }
            }
            return ResponseRead.Success(output.toString(StandardCharsets.UTF_8.name()))
        }
    }

    private fun ensureActive(cancellation: ProviderCancellation, deadline: Long) {
        if (cancellation.isCancelled) throw ProviderCancelledException()
        if (System.nanoTime() >= deadline) throw ProviderTimedOutException()
    }

    private class ActiveConnection {
        @Volatile private var connection: HttpURLConnection? = null

        fun set(value: HttpURLConnection) {
            connection = value
        }

        fun clear(value: HttpURLConnection?) {
            if (connection === value) connection = null
        }

        fun disconnect() {
            connection?.disconnect()
        }
    }

    private sealed interface ResponseRead {
        data class Success(val body: String) : ResponseRead
        data class Failure(val kind: ProviderFailureKind) : ResponseRead
    }

    private class ProviderCancelledException : IOException()
    private class ProviderTimedOutException : IOException()

    internal companion object {
        val REQUEST_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(
            4,
            ThreadFactory { runnable ->
                Thread(runnable, "provider-polish").apply { isDaemon = true }
            },
        )
        const val MAX_REQUEST_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
