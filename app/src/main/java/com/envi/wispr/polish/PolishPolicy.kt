package com.envi.wispr.polish

import android.os.Parcel
import android.os.Parcelable
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.SelfHostedProtocol

/**
 * One immutable snapshot of what the user chose on the AI Polish screen, taken by the main process
 * at session start and carried on every request over the binder. The engine never reads a
 * preference: SharedPreferences is cached per process, so a live `:polish` process would otherwise
 * keep the values it read when it was created (issue #69, measured 2026-09-01).
 *
 * The API key is deliberately NOT here. It is a credential, not policy; the engine reads it from
 * the Keystore-backed store at request time, which is process-safe.
 *
 * Hand-written Parcelable: `writeToParcel` and `CREATOR` sit side by side so the field order
 * cannot drift apart. No parcelize plugin is applied in this app.
 */
sealed class PolishPolicy : Parcelable {
    /** AI Polish is off; the deterministic rules run alone. */
    object Off : PolishPolicy()

    /** Polish on this phone with the local S1 model. */
    object LocalS1 : PolishPolicy()

    /** The user chose a cloud mode but no valid provider selection exists. Fails open to rules. */
    object CloudUnconfigured : PolishPolicy()

    data class Cloud(
        val provider: Provider,
        val model: String,
        val endpoint: String?,
        val protocol: SelfHostedProtocol,
    ) : PolishPolicy()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        when (this) {
            Off -> dest.writeByte(TAG_OFF)
            LocalS1 -> dest.writeByte(TAG_LOCAL_S1)
            CloudUnconfigured -> dest.writeByte(TAG_CLOUD_UNCONFIGURED)
            is Cloud -> {
                dest.writeByte(TAG_CLOUD)
                dest.writeString(provider.name)
                dest.writeString(model)
                dest.writeString(endpoint)
                dest.writeString(protocol.name)
            }
        }
    }

    companion object {
        private const val TAG_OFF: Byte = 0
        private const val TAG_LOCAL_S1: Byte = 1
        private const val TAG_CLOUD_UNCONFIGURED: Byte = 2
        private const val TAG_CLOUD: Byte = 3

        @JvmField
        val CREATOR: Parcelable.Creator<PolishPolicy> = object : Parcelable.Creator<PolishPolicy> {
            override fun createFromParcel(source: Parcel): PolishPolicy = when (val tag = source.readByte()) {
                TAG_OFF -> Off
                TAG_LOCAL_S1 -> LocalS1
                TAG_CLOUD_UNCONFIGURED -> CloudUnconfigured
                TAG_CLOUD -> Cloud(
                    provider = Provider.valueOf(checkNotNull(source.readString())),
                    model = checkNotNull(source.readString()),
                    endpoint = source.readString(),
                    protocol = SelfHostedProtocol.valueOf(checkNotNull(source.readString())),
                )
                else -> throw IllegalArgumentException("unknown PolishPolicy tag $tag")
            }

            override fun newArray(size: Int): Array<PolishPolicy?> = arrayOfNulls(size)
        }
    }
}
