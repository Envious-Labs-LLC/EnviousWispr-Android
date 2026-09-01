package com.envi.wispr.polish

import android.os.Parcel
import android.os.Parcelable

/**
 * The whole answer to one polish request. [engine] is the History label vocabulary
 * (`PolishEngineLabels` or an engine's display name); [statusCode] is 0 when no HTTP status applies.
 */
data class PolishOutcome(
    val requestId: Long,
    val text: String,
    val engine: String,
    val reason: PolishReason,
    val statusCode: Int,
    val latencyMs: Long,
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(requestId)
        dest.writeString(text)
        dest.writeString(engine)
        dest.writeString(reason.name)
        dest.writeInt(statusCode)
        dest.writeLong(latencyMs)
    }

    /** Content-free on purpose: the text never reaches a log line through this. */
    override fun toString(): String =
        "PolishOutcome(requestId=$requestId, engine=$engine, reason=$reason, statusCode=$statusCode, latencyMs=$latencyMs, chars=${text.length})"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<PolishOutcome> = object : Parcelable.Creator<PolishOutcome> {
            override fun createFromParcel(source: Parcel): PolishOutcome = PolishOutcome(
                requestId = source.readLong(),
                text = checkNotNull(source.readString()),
                engine = checkNotNull(source.readString()),
                reason = PolishReason.valueOf(checkNotNull(source.readString())),
                statusCode = source.readInt(),
                latencyMs = source.readLong(),
            )

            override fun newArray(size: Int): Array<PolishOutcome?> = arrayOfNulls(size)
        }
    }
}
