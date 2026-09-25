package com.envi.wispr.ui

import com.envi.wispr.audio.PcmAudio
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps a playable copy of the newest [limit] takes' audio on the phone (#373), so a take that went wrong can be
 * pulled with `adb pull` from a release build and replayed on the emulator or the Mac. The copy never leaves the
 * phone. [dir] is resolved on the caller's thread each time; null (no shared storage) keeps nothing and says so.
 * A take that was not kept throws [NotKept]; a cleanup that failed while the take WAS kept goes to [warn].
 */
internal class RecordingArchive(
    private val dir: () -> File?,
    private val warn: (String) -> Unit,
    private val limit: Int = LIMIT,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Copies [source] (raw 16 kHz mono PCM16) into the folder as a WAV and leaves at most [limit] recordings: the
     * oldest go BEFORE the new one appears.
     */
    fun keep(source: File) {
        val folder = dir() ?: throw NotKept("recordings storage unavailable")
        if (!folder.isDirectory && !folder.mkdirs()) throw NotKept("recordings folder unavailable")
        // A copy cut short by a process death leaves its part file; it is never a recording, so one that cannot be
        // removed is reported and does not stop this take.
        val leftovers = folder.listFiles { file -> file.name.endsWith(PART) }
            ?: throw NotKept("recordings folder unreadable")
        val stuckParts = leftovers.count { !it.delete() }
        if (stuckParts > 0) report("Recordings folder: $stuckParts old part files could not be removed")
        // The next take's start sweeps earlier capture files (#373 plan F1); a take lost that way says so.
        if (!source.isFile) throw NotKept("recording was gone before it could be kept")
        val name = "${stamp()}-${takeName(source)}"
        val part = File(folder, "$name$PART")
        try {
            FileOutputStream(part).use { out ->
                out.write(header(source.length()))
                source.inputStream().use { it.copyTo(out) }
            }
            prune(folder, room = limit - 1)
            if (!part.renameTo(File(folder, "$name$WAV"))) throw NotKept("rename failed")
        } finally {
            // After a successful rename there is no part file; one still here after the delete is reported.
            if (part.exists() && !part.delete()) report("Recordings folder: a part file could not be removed")
        }
    }

    /** A warning that throws must neither stop the copy nor replace the failure already in flight. */
    private fun report(message: String) {
        runCatching { warn(message) }
    }

    private fun prune(folder: File, room: Int) {
        val kept = folder.listFiles { file -> file.name.endsWith(WAV) }?.sortedBy { it.name }
            ?: throw NotKept("recordings folder unreadable")
        val stuck = kept.dropLast(room).count { !it.delete() }
        if (stuck > 0) throw NotKept("$stuck old recordings could not be removed")
    }

    /** UTC to the millisecond, so neither a time-zone change nor two takes in one second reorder the prune. */
    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(now()))

    private fun takeName(source: File): String = source.name.removeSuffix(".pcm").removePrefix("recording-take-")

    /** Why a take was not kept or the folder not cleaned; its message names no content and no path. */
    class NotKept(reason: String) : Exception(reason)

    companion object {
        const val LIMIT = 10
        const val FOLDER = "recordings"
        private const val WAV = ".wav"
        private const val PART = ".part"

        /** The 44-byte RIFF header for [dataBytes] of 16 kHz mono PCM16. */
        fun header(dataBytes: Long): ByteArray {
            val channels = 1
            val bits = PcmAudio.BYTES_PER_SAMPLE * 8
            val byteRate = PcmAudio.SAMPLE_RATE * channels * PcmAudio.BYTES_PER_SAMPLE
            val data = dataBytes.coerceAtMost(Int.MAX_VALUE - 36L).toInt()
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + data)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1); putShort(channels.toShort()); putInt(PcmAudio.SAMPLE_RATE); putInt(byteRate)
                putShort((channels * PcmAudio.BYTES_PER_SAMPLE).toShort()); putShort(bits.toShort())
                put("data".toByteArray(Charsets.US_ASCII)); putInt(data)
            }.array()
        }
    }
}
