package com.envi.wispr.providers

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/*
 * Harness Contract: the local HTTP servers and body fixtures shared by `ProviderPolishClientTest` and
 * `ProviderModelDiscoveryClientTest` (#189). Nothing here asserts; a defect here makes every wire-level row
 * lie in the same direction, which is why the servers stay this small.
 */

internal fun withServer(
    response: String,
    status: Int = 200,
    headers: Map<String, String> = emptyMap(),
    basePath: String = "",
    beforeResponse: CountDownLatch? = null,
    chunkDelayMs: Long = 0,
    /** Read the request, then close without a status line: the shape of a dead upstream (#61). */
    closeBeforeStatus: Boolean = false,
    inspect: (TestRequest) -> Unit = {},
    block: (String) -> Unit,
) {
    val server = TestServer(status, response, headers, beforeResponse, chunkDelayMs, inspect, closeBeforeStatus)
    try {
        block("http://127.0.0.1:${server.port}$basePath")
    } finally {
        server.close()
    }
}

internal class ScriptedServer(
    /** Answers one request: (status, body). Runs on the connection's own thread. */
    private val respond: (TestRequest) -> Pair<Int, String>,
    private val holdMs: Long = 0,
    private val connections: Int = 64,
) : AutoCloseable {
    private val socket = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val acceptor = Executors.newSingleThreadExecutor()
    private val handlers = Executors.newCachedThreadPool()
    val requests = java.util.Collections.synchronizedList(mutableListOf<TestRequest>())
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val port: Int get() = socket.localPort
    val base: String get() = "http://127.0.0.1:$port"

    init {
        acceptor.submit {
            repeat(connections) {
                val connection = try { socket.accept() } catch (_: Exception) { return@submit }
                handlers.submit {
                    try {
                        connection.use {
                            val now = inFlight.incrementAndGet()
                            maxInFlight.accumulateAndGet(now, ::maxOf)
                            try {
                                val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.ISO_8859_1))
                                val requestLine = reader.readLine() ?: return@use
                                val parts = requestLine.split(' ', limit = 3)
                                val headers = buildMap {
                                    while (true) {
                                        val line = reader.readLine() ?: break
                                        if (line.isEmpty()) break
                                        val colon = line.indexOf(':')
                                        if (colon > 0) put(line.substring(0, colon).lowercase(), line.substring(colon + 1).trim())
                                    }
                                }
                                val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
                                val body = CharArray(bodyLength)
                                var read = 0
                                while (read < bodyLength) {
                                    val count = reader.read(body, read, bodyLength - read)
                                    if (count < 0) break
                                    read += count
                                }
                                val request = TestRequest(parts[0], parts.getOrElse(1) { "" }, headers, String(body, 0, read))
                                requests += request
                                if (holdMs > 0) Thread.sleep(holdMs)
                                val (status, response) = respond(request)
                                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                                val writer = PrintWriter(OutputStreamWriter(it.getOutputStream(), StandardCharsets.ISO_8859_1))
                                writer.print("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                                writer.flush()
                                it.getOutputStream().write(bytes)
                                it.getOutputStream().flush()
                            } finally {
                                inFlight.decrementAndGet()
                            }
                        }
                    } catch (_: Exception) {
                        // A cancelled probe closes its socket mid-write; that is the shape under test.
                    }
                }
            }
        }
    }

    override fun close() {
        socket.close()
        acceptor.shutdownNow()
        handlers.shutdownNow()
    }
}

internal fun openAiList(vararg ids: String) = "{\"data\":[" + ids.joinToString(",") { "{\"id\":\"$it\",\"object\":\"model\"}" } + "]}"

internal fun probedModel(request: TestRequest): String = Regex("\"model\":\"([^\"]+)\"").find(request.body)?.groupValues?.get(1) ?: request.path.substringAfterLast('/').substringBefore(':')

/**
 * A successful reply in each provider's own envelope. **It must match the provider under test**: since
 * #104 the probe judges a body with the polish parser, so serving an OpenAI envelope to a Claude probe
 * makes every model read UNAVAILABLE. That mismatch was live in four fixtures and only went red once
 * the crude label scan was replaced.
 */
internal fun okBody(provider: Provider) = when (provider) {
    Provider.OPENAI -> "{\"output\":[{\"content\":[{\"text\":\"clean result\"}]}]}"
    Provider.GEMINI -> "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"clean result\"}]}}]}"
    Provider.CLAUDE -> "{\"content\":[{\"type\":\"text\",\"text\":\"clean result\"}]}"
    Provider.SELF_HOSTED_POLISH -> "{\"choices\":[{\"message\":{\"content\":\"clean result\"}}]}"
}

internal fun jsonQuoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

internal data class TestRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

internal class TestServer(
    private val status: Int,
    private val response: String,
    private val responseHeaders: Map<String, String>,
    private val beforeResponse: CountDownLatch?,
    private val chunkDelayMs: Long,
    private val inspect: (TestRequest) -> Unit,
    private val closeBeforeStatus: Boolean = false,
) : AutoCloseable {
    private val socket = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    private val executor = Executors.newSingleThreadExecutor()
    val port: Int get() = socket.localPort

    init {
        executor.submit {
            try {
                // The JDK client retries once on an EOF before the status line; hang up on the retry too.
                repeat(if (closeBeforeStatus) 3 else 1) { socket.accept().use { connection ->
                    val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1))
                    val requestLine = reader.readLine() ?: return@use
                    val parts = requestLine.split(' ', limit = 3)
                    val headers = buildMap {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) put(line.substring(0, colon).lowercase(), line.substring(colon + 1).trim())
                        }
                    }
                    val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = CharArray(bodyLength)
                    var read = 0
                    while (read < bodyLength) {
                        val count = reader.read(body, read, bodyLength - read)
                        if (count < 0) break
                        read += count
                    }
                    inspect(TestRequest(parts[0], parts.getOrElse(1) { "" }, headers, String(body, 0, read)))
                    if (closeBeforeStatus) return@use
                    beforeResponse?.countDown()
                    val bytes = response.toByteArray(StandardCharsets.UTF_8)
                    val writer = PrintWriter(OutputStreamWriter(connection.getOutputStream(), StandardCharsets.ISO_8859_1))
                    writer.print("HTTP/1.1 $status Test\r\n")
                    writer.print("Content-Length: ${bytes.size}\r\n")
                    responseHeaders.forEach { (name, value) -> writer.print("$name: $value\r\n") }
                    writer.print("Connection: close\r\n\r\n")
                    writer.flush()
                    if (chunkDelayMs > 0 && bytes.size > 1) {
                        val midpoint = bytes.size / 2
                        connection.getOutputStream().write(bytes, 0, midpoint)
                        connection.getOutputStream().flush()
                        Thread.sleep(chunkDelayMs)
                        connection.getOutputStream().write(bytes, midpoint, bytes.size - midpoint)
                    } else {
                        connection.getOutputStream().write(bytes)
                    }
                    connection.getOutputStream().flush()
                } }
            } catch (_: Exception) {
                // The client may disconnect intentionally during cancellation tests.
            }
        }
    }

    override fun close() {
        socket.close()
        executor.shutdownNow()
    }
}
