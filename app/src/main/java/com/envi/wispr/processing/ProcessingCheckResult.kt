package com.envi.wispr.processing

import android.os.Parcel
import android.os.Parcelable

internal enum class ProcessingCheckStatus { AVAILABLE, MODEL_MISSING, NOT_IMPLEMENTED, RUNTIME_FAILED, LOAD_FAILED, CANARY_FAILED, CANCELLED, EXPIRED }

/** Contains no canary text, user text, paths or vendor diagnostics. */
internal data class ProcessingCheckResult(
    val operationId: Long,
    val backend: ProcessingBackend,
    val contextId: String,
    val status: ProcessingCheckStatus,
    val evidenceStamp: ProcessingEvidenceStamp? = null,
) : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(operationId); dest.writeString(backend.wire); dest.writeString(contextId); dest.writeString(status.name); dest.writeString(evidenceStamp?.encode())
    }
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<ProcessingCheckResult> {
            override fun createFromParcel(source: Parcel) = ProcessingCheckResult(
                source.readLong(), ProcessingBackend.fromWire(checkNotNull(source.readString())),
                checkNotNull(source.readString()), ProcessingCheckStatus.valueOf(checkNotNull(source.readString())), ProcessingEvidenceStamp.decode(source.readString()),
            )
            override fun newArray(size: Int): Array<ProcessingCheckResult?> = arrayOfNulls(size)
        }
    }
}
