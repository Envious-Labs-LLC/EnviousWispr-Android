package com.envi.wispr.recovery

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.EnglishSpelling
import com.envi.wispr.vocabulary.CustomTerm
import java.util.UUID

/** Protocol shared only by the opt-in lab app and its test APK. */
internal object RecoveryQualificationPeer {
    const val DESCRIPTOR = "com.envi.wispr.recovery.Qualification.v1"
    const val CREATE = 1
    const val CLOSE = 2
    const val KILL_SELF = 3
    const val HELLO = 4

    internal fun fixture(take: UUID): FrozenRecoveryContext = FrozenRecoveryContext.freeze(
        take, 1L, "qualification-recipe-v1", "qualification-pcm-v1",
        CleanupOptions(true, false, true, EnglishSpelling.BRITISH, setOf("colour")),
        listOf(CustomTerm("GitHub", listOf("git hub"), "synthetic", 50, true, false, 0.92, 7L, false)),
    )

    fun <T> requireReady(result: RecoveryStoreResult<T>): T = when (result) {
        is RecoveryStoreResult.Ready -> result.value
        RecoveryStoreResult.Busy -> error("Qualification store unexpectedly busy")
        is RecoveryStoreResult.Unavailable -> error("Qualification store unavailable: ${result.reason}")
    }
}

/** Nonexported component compiled only into the explicitly selected qualification app. */
class RecoveryQualificationService : Service() {
    override fun onBind(intent: Intent): IBinder {
        check(Application.getProcessName() == packageName + ":audio") { "Wrong qualification process" }
        val nonce = checkNotNull(intent.getStringExtra("nonce"))
        UUID.fromString(nonce)
        val context = applicationContext
        val store = RecoveryAudioStore(context)
        val monitor = Any()
        var held: RecoveryAudioStore.Reservation? = null
        val peer = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code !in RecoveryQualificationPeer.CREATE..RecoveryQualificationPeer.HELLO) return super.onTransact(code, data, reply, flags)
                check(Binder.getCallingUid() == Process.myUid()) { "Qualification caller UID mismatch" }
                data.enforceInterface(RecoveryQualificationPeer.DESCRIPTOR)
                check(data.readString() == nonce) { "Qualification nonce mismatch" }
                if (code == RecoveryQualificationPeer.KILL_SELF) {
                    check(data.readInt() == Process.myPid()) { "Qualification peer identity changed" }
                    Process.killProcess(Process.myPid())
                    return true
                }
                if (code == RecoveryQualificationPeer.HELLO) {
                    checkNotNull(reply).writeNoException()
                    reply.writeInt(Process.myUid())
                    reply.writeInt(Process.myPid())
                    reply.writeString(Application.getProcessName())
                    return true
                }
                synchronized(monitor) {
                    when (code) {
                        RecoveryQualificationPeer.CREATE -> {
                            check(held == null) { "Qualification peer already owns a reservation" }
                            val take = UUID(data.readLong(), data.readLong())
                            val reservation = RecoveryQualificationPeer.requireReady(store.reserveOnWorker(RecoveryQualificationPeer.fixture(take)))
                            held = reservation
                            RecoveryQualificationPeer.requireReady(reservation.prepareOnWorker())
                            val ref = reservation.ref
                            checkNotNull(reply).writeNoException()
                            reply.writeLong(ref.storeId.mostSignificantBits)
                            reply.writeLong(ref.storeId.leastSignificantBits)
                            reply.writeInt(ref.slot)
                            reply.writeLong(ref.identity.generation)
                            reply.writeByteArray(ref.identity.context.copyBytes())
                        }
                        RecoveryQualificationPeer.CLOSE -> {
                            held?.closeOnWorker()
                            held = null
                            checkNotNull(reply).writeNoException()
                        }
                    }
                }
                return true
            }
        }
        return peer
    }
}
