package com.envi.wispr.processing

import android.os.Parcel
import android.os.Parcelable

/** A user-request fact, never a qualification result or a guess from a saved preference. */
internal data class ProcessingUsage(
    val backend: ProcessingBackend?,
    val contextId: String,
    val preference: ProcessingPreference,
    val acceptedLocalText: Boolean,
    val failedBackendCodes: String = "",
    val runtimeFailed: Boolean = false,
    val modelId: String = "s1-mini",
    val artifactStamp: String = "",
    val artifactSha256: String = "",
    val loadStamp: ProcessingEvidenceStamp? = null,
    val generationStamp: ProcessingEvidenceStamp? = null,
    val generationFailed: Boolean = false,
) : Parcelable {
    init {
        require(contextId.length == 64 && contextId.all { it in '0'..'9' || it in 'a'..'f' })
        require(!acceptedLocalText || backend != null)
        val failed = if (failedBackendCodes.isEmpty()) emptyList() else failedBackendCodes.split(',').map(ProcessingBackend::fromWire)
        require(failed.distinct().size == failed.size)
        require(artifactSha256.isEmpty() || artifactSha256.length == 64 && artifactSha256.all { it in '0'..'9' || it in 'a'..'f' })
    }
    val failedBackends: Set<ProcessingBackend> get() = if (failedBackendCodes.isEmpty()) emptySet() else failedBackendCodes.split(',').map(ProcessingBackend::fromWire).toSet()
    val preferenceRevision: Long get() = preference.retryRevision
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(backend?.wire)
        dest.writeString(contextId)
        dest.writeString(preference.encode())
        dest.writeInt(if (acceptedLocalText) 1 else 0)
        dest.writeString(failedBackendCodes); dest.writeInt(if (runtimeFailed) 1 else 0)
        dest.writeString(modelId); dest.writeString(artifactStamp); dest.writeString(artifactSha256)
        dest.writeString(loadStamp?.encode()); dest.writeString(generationStamp?.encode()); dest.writeInt(if (generationFailed) 1 else 0)
    }
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<ProcessingUsage> {
            override fun createFromParcel(source: Parcel) = ProcessingUsage(
                source.readString()?.let(ProcessingBackend::fromWire), checkNotNull(source.readString()),
                ProcessingPreference.decode(checkNotNull(source.readString())), source.readInt() == 1,
                checkNotNull(source.readString()), source.readInt() == 1, checkNotNull(source.readString()), checkNotNull(source.readString()), checkNotNull(source.readString()),
                ProcessingEvidenceStamp.decode(source.readString()), ProcessingEvidenceStamp.decode(source.readString()), source.readInt() == 1,
            )
            override fun newArray(size: Int): Array<ProcessingUsage?> = arrayOfNulls(size)
        }
    }
}
