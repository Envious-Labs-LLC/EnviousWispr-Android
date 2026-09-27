package com.envi.wispr.debug

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One process's log writer (#378 D4, D6): the only code that writes that process's log files.
 *
 * Callers never touch disk. They hand a LAZY [Entry] to [offer]; nothing in an entry's text is built until
 * this writer, holding the process's [ProcessLogLock] and having just seen the Detailed log flag present,
 * evaluates it. With the flag absent the batch is discarded unevaluated, so no log string and no word
 * string exists while Detailed log is Off. The queue is bounded ([capacity]); overflow drops the OLDEST
 * entry and is counted, and the count is written as a `dropped=N` line and reported on the next fence.
 *
 * The writer thread sleeps on its queue: it wakes for an entry or a [Signal] and for nothing else, so it
 * adds no idle wake (`architecture-rules.md` RULE: no-idle-cost).
 */
internal class LogWriter(
    private val files: LogFiles,
    private val process: String,
    private val lock: ProcessLogLock,
    private val wallClock: () -> Long,
    private val link: (from: File, to: File) -> Unit,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val lockTimeoutMs: Long = LOCK_TIMEOUT_MS,
    private val onWriteFailure: (String) -> Unit = {},
    private val onRefreshed: () -> Unit = {},
) {
    /** One log line, its text built only by the writer. [bootMs] is the phone-wide boot clock at enqueue. */
    class Entry(
        val wallMs: Long,
        val bootMs: Long,
        val level: Char,
        val tag: String,
        val takeId: String?,
        val text: () -> String,
    )

    /** Work for the writer thread that is not a line. */
    sealed interface Signal {
        /** Re-read the flag into the hint (an observer event, or a take admission). */
        data object Refresh : Signal
        /** A Share fence may have been written: read it and, if new, drain, pin and acknowledge. */
        data object Fence : Signal
        /** Write everything queued before this, then count [done] down (the crash hook's bounded flush). */
        class Flush(val done: CountDownLatch) : Signal
    }

    private val queue = LinkedBlockingDeque<Any>()
    private val lines = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val hint = AtomicBoolean(false)

    /** Entries discarded because the flag was absent, since the last acknowledged fence. */
    private var discardedByOff = 0L
    private var droppedSinceAck = 0L
    private var lastAckedFence: String? = null
    private var output: FileOutputStream? = null
    private val wakes = AtomicLong(0)

    /** The process-local hint callers read; the writer is the only thing that sets it. */
    val enabledHint: Boolean get() = hint.get()

    /** How many times the writer thread has woken; the idle oracle reads it (#378 §11). */
    val wakeCount: Long get() = wakes.get()

    /** Queues [entry]; never blocks, never touches disk. The oldest entry is dropped when full. */
    fun offer(entry: Entry) {
        if (lines.incrementAndGet() > capacity) {
            if (queue.pollFirstLine() != null) {
                lines.decrementAndGet()
                dropped.incrementAndGet()
            }
        }
        queue.offerLast(entry)
    }

    fun signal(signal: Signal) {
        queue.offerLast(signal)
    }

    /** Asks the writer to write what it holds and waits at most [timeoutMs]; true when it acknowledged. */
    fun flush(timeoutMs: Long): Boolean {
        val done = CountDownLatch(1)
        signal(Signal.Flush(done))
        return runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    /** Starts the one daemon thread. Idempotent per instance is the caller's job ([LocalLog] owns one). */
    fun start(): Thread = Thread({ loop() }, "local-log-$process").apply {
        isDaemon = true
        start()
    }

    private fun loop() {
        while (true) {
            val first = runCatching { queue.takeFirst() }.getOrNull() ?: continue
            wakes.incrementAndGet()
            val batch = ArrayList<Any>()
            batch.add(first)
            queue.drainTo(batch, MAX_BATCH)
            runCatching { process(batch) }.onFailure { onWriteFailure("Local log batch failed: ${it.javaClass.simpleName}") }
        }
    }

    /** Runs one batch; entries and signals keep their order. Visible for the JVM tests. */
    internal fun process(batch: List<Any>) {
        val entries = ArrayList<Entry>()
        for (item in batch) {
            when (item) {
                is Entry -> {
                    lines.decrementAndGet()
                    entries.add(item)
                }
                is Signal -> {
                    writeEntries(entries)
                    entries.clear()
                    handle(item)
                }
            }
        }
        writeEntries(entries)
    }

    private fun handle(signal: Signal) {
        when (signal) {
            Signal.Refresh -> {
                hint.set(files.detailedLogFlag.exists())
                onRefreshed()
            }
            Signal.Fence -> answerFence()
            is Signal.Flush -> signal.done.countDown()
        }
    }

    private fun writeEntries(entries: List<Entry>) {
        val overflow = dropped.getAndSet(0)
        if (entries.isEmpty() && overflow == 0L) return
        lock.withLock(lockTimeoutMs) {
            val on = files.detailedLogFlag.exists()
            hint.set(on)
            droppedSinceAck += overflow
            if (!on) {
                discardedByOff += entries.size
                return@withLock
            }
            val text = StringBuilder()
            if (overflow > 0) text.append(render(wallClock(), 0L, 'W', TAG, null, "dropped=$overflow queued lines"))
            for (entry in entries) {
                val body = runCatching(entry.text).getOrElse { "<line failed: ${it.javaClass.simpleName}>" }
                text.append(render(entry.wallMs, entry.bootMs, entry.level, entry.tag, entry.takeId, body))
            }
            append(text.toString().toByteArray(Charsets.UTF_8))
        } ?: run {
            // The lock stayed busy past the bound (the Off barrier holding it, or a stuck holder): these lines
            // are not written, and the next fence reports them.
            droppedSinceAck += entries.size + overflow
        }
    }

    private fun append(bytes: ByteArray) {
        val current = files.current(process)
        if (current.length() + bytes.size > LogFiles.MAX_FILE_BYTES && current.length() > 0) rotate()
        val out = output?.takeIf { current.exists() } ?: FileOutputStream(current, true).also {
            runCatching { output?.close() }
            output = it
        }
        out.write(bytes)
        out.flush()
    }

    /** Rename-only rotation under the lock: files are appended, renamed or unlinked, never rewritten. */
    private fun rotate() {
        runCatching { output?.close() }
        output = null
        files.rotated(process, LogFiles.ROTATIONS).delete()
        for (index in LogFiles.ROTATIONS - 1 downTo 1) {
            val from = files.rotated(process, index)
            if (from.exists()) from.renameTo(files.rotated(process, index + 1))
        }
        files.current(process).let { if (it.exists()) it.renameTo(files.rotated(process, 1)) }
    }

    /**
     * The fence (#378 D6): if [LogFiles.fence] names an id this writer has not answered and it is not the
     * cancellation id, everything queued before it has already been processed (signals keep queue order),
     * so pin an immutable snapshot of this process's files under the lock and acknowledge with the status.
     */
    private fun answerFence() {
        val id = runCatching { files.fence.readText().trim() }.getOrNull()
        if (id.isNullOrEmpty() || id == LogFiles.CANCELLED_FENCE || id == lastAckedFence) return
        lock.withLock(lockTimeoutMs) {
            if (runCatching { files.fence.readText().trim() }.getOrNull() != id) return@withLock
            val dir = files.snapshot(id, process).apply { mkdirs() }
            val lengths = StringBuilder()
            for (file in files.setOf(process)) {
                if (!file.exists()) continue
                val length = file.length()
                val pinned = File(dir, file.name)
                runCatching { link(file, pinned) }.onSuccess { lengths.append(file.name).append(' ').append(length).append('\n') }
            }
            File(dir, LENGTHS).writeText(lengths.toString())
            val status = when {
                droppedSinceAck > 0 -> "dropped"
                discardedByOff > 0 -> "discarded-by-Off"
                else -> "written"
            }
            writeAtomically(files.ack(process), "$id $status $droppedSinceAck\n")
            lastAckedFence = id
            droppedSinceAck = 0
            discardedByOff = 0
        }
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text)
        temp.renameTo(target)
    }

    private fun LinkedBlockingDeque<Any>.pollFirstLine(): Entry? {
        val iterator = iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is Entry) {
                iterator.remove()
                return item
            }
        }
        return null
    }

    private fun render(wallMs: Long, bootMs: Long, level: Char, tag: String, takeId: String?, body: String): String =
        "${formatWall(wallMs)} b=$bootMs [$process] $level [$tag] take=${takeId ?: "-"} $body\n"

    private fun formatWall(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ms))

    companion object {
        private const val TAG = "LocalLog"
        const val LENGTHS = "lengths"
        const val DEFAULT_CAPACITY = 8_192
        private const val MAX_BATCH = 256
        const val LOCK_TIMEOUT_MS = 2_000L
    }
}
