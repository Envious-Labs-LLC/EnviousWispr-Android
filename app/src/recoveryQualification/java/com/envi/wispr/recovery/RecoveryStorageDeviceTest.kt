package com.envi.wispr.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.vocabulary.CustomTerm
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.KeyGenerator
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Product Outcome: real same-UID Android keys/claims must survive process death and prevent stale cleanup.
 * Harness Contract: the unsent-callback control checks the required deadline primitive only.
 * Only disposable fresh lab apps. No microphone, editor, native-model or complete recovery UAT claim.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecoveryStorageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.noBackupFilesDir, "protected-recovery.v1")

    @Test fun aFirstPublicationCannotBePoisonedByAnEarlySnapshot() {
        assertNondebug()
        val files = RecoveryStoreFiles(context)
        assertTrue("Fresh disposable app required for initialization witness", files.createRootIfAbsent())
        files.createInitialLockFiles()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val early = executor.submit<RecoveryStoreResult<RecoveryStoreSnapshot>> { RecoveryAudioStore(context).snapshotOnWorker() }.get(10, TimeUnit.SECONDS)
            assertTrue("Pre-publication snapshot must refuse without taking a coordination lease", early is RecoveryStoreResult.Unavailable)
            val claim = acquired(RecoveryFileClaim.tryAcquire(files, RecoveryLockName.STORE))
            try {
                files.writeLedger(RecoveryLedger.of(UUID.randomUUID(), 1L, listOf(null, null)))
            } finally {
                assertEquals(RecoveryClaimRelease.RELEASED, claim.release())
            }
            val snapshot = ready(RecoveryAudioStore(context).snapshotOnWorker())
            assertEquals(listOf(null, null), snapshot.ledger.slots)
        } finally { executor.shutdownNow() }
    }

    @Test fun bRealKeysClaimsDeathAndStaleCleanupPreserveTheCurrentTake() {
        assertNondebug()
        val nonce = checkNotNull(InstrumentationRegistry.getArguments().getString("recoveryProbeNonce")) { "Stage recoveryProbeNonce before running qualification" }
        UUID.fromString(nonce)
        val gotPeer = CountDownLatch(1)
        val died = CountDownLatch(1)
        var peer: IBinder? = null
        var peerPid = -1
        val stoppingPeer = AtomicBoolean()
        val peerFailure = AtomicReference<Throwable?>()
        val executor = Executors.newFixedThreadPool(2)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (stoppingPeer.get()) return
                executor.execute {
                    try {
                        check(peer == null) { "Multiple qualification peers; refusing to choose" }
                        call(service, nonce, RecoveryQualificationPeer.HELLO, {}) {
                            check(readInt() == Process.myUid()) { "Peer must execute as the target application's UID" }
                            peerPid = readInt()
                            check(peerPid > 0 && peerPid != Process.myPid()) { "Peer must have a distinct actual PID" }
                            check(readString() == context.packageName + ":audio") { "Wrong target process" }
                        }
                        service.linkToDeath({ died.countDown() }, 0)
                        peer = service
                    } catch (failure: Throwable) {
                        peerFailure.set(failure)
                    } finally {
                        gotPeer.countDown()
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        var second: RecoveryAudioStore.Reservation? = null
        var replacement: RecoveryAudioStore.Reservation? = null
        var old: RecoveryRecordRef? = null
        var bodyFailure: Throwable? = null
        var oracle: KernelReleaseWitness? = null
        try {
            val intent = Intent(context, RecoveryQualificationService::class.java)
                .setIdentifier(nonce).putExtra("nonce", nonce)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            assertTrue("Private qualification service binding refused", bound)
            assertTrue("Private audio peer did not complete HELLO", gotPeer.await(15, TimeUnit.SECONDS))
            peerFailure.get()?.let { throw AssertionError("Qualification peer identity handshake failed", it) }
            val remote = checkNotNull(peer)
            val take = UUID.randomUUID()
            old = executor.submit<RecoveryRecordRef> {
                call(remote, nonce, RecoveryQualificationPeer.CREATE, { writeLong(take.mostSignificantBits); writeLong(take.leastSignificantBits) }) {
                    val store = UUID(readLong(), readLong())
                    val slot = readInt()
                    val generation = readLong()
                    val digest = RecoveryContextDigest.of(checkNotNull(createByteArray()))
                    RecoveryRecordRef(store, slot, RecoveryRecordIdentity(take, generation, digest))
                }
            }.get(30, TimeUnit.SECONDS)
            val original = checkNotNull(old)
            val files = RecoveryStoreFiles(context)
            val inode = files.lockIdentity(RecoveryLockName.MAIN_0)
            assertEquals(0, original.slot)
            val store = RecoveryAudioStore(context)
            second = ready(store.reserveOnWorker(RecoveryQualificationPeer.fixture(UUID.randomUUID())))
            ready(checkNotNull(second).prepareOnWorker())
            val full = store.reserveOnWorker(RecoveryQualificationPeer.fixture(UUID.randomUUID()))
            assertEquals(RecoveryStoreFailure.CAPACITY, (full as RecoveryStoreResult.Unavailable).reason)
            ready(store.markTerminalOnWorker(original, RecoveryDisposition.DISCARDED))
            val refused = store.cleanupOnWorker(original)
            // Observe the actual resources before asserting the guard's return value.
            assertContext(original)
            assertTrue("Cleanup must preserve a live owner's key and file: $refused", refused is RecoveryStoreResult.Busy)
            oracle = KernelReleaseWitness(files.lockFile(RecoveryLockName.MAIN_0)).also { it.start() }
            stoppingPeer.set(true)
            try {
                executor.submit {
                    call(remote, nonce, RecoveryQualificationPeer.KILL_SELF, { writeInt(peerPid) }) { Unit }
                }.get(10, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.ExecutionException) {
                // Binder death is expected; the kernel lock witness below supplies release proof.
            }
            assertTrue("Creator peer did not die", died.await(10, TimeUnit.SECONDS))
            checkNotNull(oracle).awaitSuccess()
            assertContext(original) // Main reads the UID-owned key after its creating peer died.
            ready(store.cleanupOnWorker(original))
            assertNull(keyStore().getKey(alias(original), null))
            assertFalse(contextFile(original.slot).exists())
            replacement = ready(store.reserveOnWorker(RecoveryQualificationPeer.fixture(UUID.randomUUID())))
            val current = checkNotNull(replacement)
            ready(current.prepareOnWorker())
            assertEquals(original.slot, current.ref.slot)
            assertTrue(current.ref.identity.generation > original.identity.generation)
            assertEquals(inode, files.lockIdentity(RecoveryLockName.MAIN_0))
            val staleMark = store.markTerminalOnWorker(original, RecoveryDisposition.DISCARDED)
            val staleCleanup = store.cleanupOnWorker(original)
            assertContext(current.ref)
            assertEquals(current.ref.identity, ready(store.snapshotOnWorker()).ledger.slots[current.ref.slot]!!.identity)
            assertEquals(RecoveryStoreFailure.STALE_IDENTITY, (staleMark as RecoveryStoreResult.Unavailable).reason)
            assertEquals(RecoveryStoreFailure.STALE_IDENTITY, (staleCleanup as RecoveryStoreResult.Unavailable).reason)
            assertTrue(current.prepareOnWorker() is RecoveryStoreResult.Unavailable)
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            val failures = ArrayList<Throwable>()
            fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures.add(failure) } }
            attempt {
                stoppingPeer.set(true)
                val remaining = peer
                if (remaining?.isBinderAlive == true) {
                    try {
                        executor.submit { call(remaining, nonce, RecoveryQualificationPeer.KILL_SELF, { writeInt(peerPid) }) { Unit } }.get(10, TimeUnit.SECONDS)
                    } catch (_: java.util.concurrent.ExecutionException) { /* Expected death reply; do not resend. */ }
                    assertTrue("Owned qualification peer must exit during failure cleanup", died.await(10, TimeUnit.SECONDS))
                }
            }
            var sameInodeSafe = oracle == null
            attempt {
                sameInodeSafe = oracle?.stopAndConfirm() ?: true
                assertTrue("Oracle exit/close unconfirmed; preserve the disposable MAIN0 state", sameInodeSafe)
            }
            attempt { if (sameInodeSafe) replacement?.let { assertEquals(RecoveryReservationClose.RELEASED, it.closeOnWorker()) } }
            attempt { second?.let { assertEquals(RecoveryReservationClose.RELEASED, it.closeOnWorker()) } }
            val store = RecoveryAudioStore(context)
            listOfNotNull(old, second?.ref, replacement?.ref).forEach { ref ->
                if (!sameInodeSafe && ref.slot == 0) return@forEach
                attempt {
                    when (val marked = store.markTerminalOnWorker(ref, RecoveryDisposition.DISCARDED)) {
                        is RecoveryStoreResult.Ready -> ready(store.cleanupOnWorker(ref))
                        RecoveryStoreResult.Busy -> error("Qualification cleanup metadata still busy")
                        is RecoveryStoreResult.Unavailable -> {
                            assertEquals(RecoveryStoreFailure.STALE_IDENTITY, marked.reason)
                            assertNotEquals(ref.identity, ready(store.snapshotOnWorker()).ledger.slots[ref.slot]?.identity)
                        }
                    }
                }
            }
            attempt { if (sameInodeSafe) assertEquals(listOf(null, null), ready(store.snapshotOnWorker()).ledger.slots) }
            attempt { if (bound) context.unbindService(connection) }
            attempt { executor.shutdownNow() }
            if (failures.isNotEmpty()) {
                val original = bodyFailure
                if (original != null) failures.forEach(original::addSuppressed)
                else throw AssertionError("Qualification cleanup failed").also { error -> failures.forEach(error::addSuppressed) }
            }
        }
    }

    // Required unsent-signal control from global test-timing.md; not product coverage.
    @Test fun cSignalFailureBoundDoesNotWaitForeverForAnUnsentCallback() {
        assertFalse(CountDownLatch(1).await(1, TimeUnit.MILLISECONDS))
    }

    @Test fun zUnknownCreationConsumesCapacityAndPositiveAnomalyPermanentlyBlocksAllocation() {
        assertNondebug()
        val store = RecoveryAudioStore(context)
        val files = RecoveryStoreFiles(context)
        val uncertain = ready(store.reserveOnWorker(RecoveryQualificationPeer.fixture(UUID.randomUUID())))
        val probe = try {
            ready(store.reserveOnWorker(RecoveryQualificationPeer.fixture(UUID.randomUUID())))
        } catch (failure: Throwable) {
            listOf<() -> Unit>(
                { uncertain.closeOnWorker() },
                { store.markTerminalOnWorker(uncertain.ref, RecoveryDisposition.DISCARDED) },
                { store.cleanupOnWorker(uncertain.ref) },
            ).forEach { action -> try { action() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) } }
            throw failure
        }
        val rogue = "enviouswispr.recovery.v1.qualification." + UUID.randomUUID()
        try {
            // Stage the real durable marker-before-call crash gap using the production codec/IO
            // primitives. No generation was invoked for this identity; this is not a lost-RPC claim.
            val metadata = acquired(RecoveryFileClaim.tryAcquire(files, RecoveryLockName.STORE))
            try {
                val ledger = files.readLedger()
                val records = ledger.slots.mapIndexed { index, old ->
                    if (index == uncertain.ref.slot) checkNotNull(old).copy(keyStage = RecoveryKeyStage.CREATE_MAY_HAVE_RUN) else old
                }
                files.writeLedger(RecoveryLedger.of(ledger.storeId, ledger.nextGeneration, records))
            } finally { assertEquals(RecoveryClaimRelease.RELEASED, metadata.release()) }
            assertEquals(RecoveryReservationClose.RELEASED, uncertain.closeOnWorker())
            ready(store.markTerminalOnWorker(uncertain.ref, RecoveryDisposition.DISCARDED))
            val retained = store.cleanupOnWorker(uncertain.ref) as RecoveryStoreResult.Unavailable
            assertEquals(RecoveryStoreFailure.KEY_UNAVAILABLE, retained.reason)
            assertEquals(RecoveryKeyStage.CREATE_MAY_HAVE_RUN, ready(store.snapshotOnWorker()).ledger.slots[uncertain.ref.slot]!!.keyStage)
            val full = store.reserveOnWorker(RecoveryQualificationPeer.fixture(UUID.randomUUID())) as RecoveryStoreResult.Unavailable
            assertEquals(RecoveryStoreFailure.CAPACITY, full.reason)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(rogue, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generator.generateKey() // An owned synthetic input anomaly, never a production alias override.
            val refused = probe.prepareOnWorker()
            val createdUnexpectedKey = keyStore().getKey(alias(probe.ref), null) is SecretKey
            assertFalse("Unowned namespace evidence must stop actual key creation: $refused", createdUnexpectedKey)
            assertTrue(refused is RecoveryStoreResult.Unavailable)
            assertEquals(RecoveryAllocationState.QUARANTINED, ready(store.snapshotOnWorker()).allocation)
            assertEquals(RecoveryReservationClose.RELEASED, probe.closeOnWorker())
            ready(store.markTerminalOnWorker(probe.ref, RecoveryDisposition.DISCARDED))
            ready(store.cleanupOnWorker(probe.ref)) // Known never-issued reservation can still retire.
            keyStore().deleteEntry(rogue)
            val afterRemoval = store.reserveOnWorker(RecoveryQualificationPeer.fixture(UUID.randomUUID())) as RecoveryStoreResult.Unavailable
            assertEquals(RecoveryStoreFailure.OWNERSHIP_UNCERTAIN, afterRemoval.reason)
            assertEquals(RecoveryAllocationState.QUARANTINED, ready(store.snapshotOnWorker()).allocation)
        } finally {
            uncertain.closeOnWorker()
            probe.closeOnWorker()
            store.markTerminalOnWorker(probe.ref, RecoveryDisposition.DISCARDED)
            store.cleanupOnWorker(probe.ref)
            keyStore().deleteEntry(rogue)
        }
    }

    private fun assertNondebug() {
        assertEquals("Qualification must use a nondebuggable app", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertEquals("com.envi.wispr", context.packageName)
    }

    private fun assertContext(ref: RecoveryRecordRef) {
        val secret = keyStore().getKey(alias(ref), null) as SecretKey
        val frame = Files.readAllBytes(contextFile(ref.slot).toPath())
        val opened = RecoveryFrameCodec.open(frame, ref.identity, secret)
        assertEquals(RecoveryFrameKind.CONTEXT, opened.header.kind)
        assertEquals(ref.identity.context, RecoveryContextCodec.digest(opened.copyPayload()))
        val decoded = RecoveryContextCodec.decode(opened.copyPayload(), ref.identity.takeId)
        assertEquals(listOf(CustomTerm("GitHub", listOf("git hub"), "synthetic", 50, true, false, 0.92, 7L, false)), decoded.terms)
        assertEquals(setOf("colour"), decoded.cleanup.spellingProtectedWords)
        val damaged = frame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(RecoveryFrameException::class.java) { RecoveryFrameCodec.open(damaged, ref.identity, secret) }
    }

    private fun contextFile(slot: Int) = File(root, "slot-$slot.context")
    private fun alias(ref: RecoveryRecordRef) = "enviouswispr.recovery.v1.${ref.storeId}.${ref.identity.generation}.${ref.identity.takeId}"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun <T> ready(result: RecoveryStoreResult<T>): T = when (result) {
        is RecoveryStoreResult.Ready -> result.value
        RecoveryStoreResult.Busy -> error("Qualification unexpectedly busy")
        is RecoveryStoreResult.Unavailable -> error("Qualification unavailable: ${result.reason}")
    }

    private fun acquired(result: RecoveryClaimResult): RecoveryFileClaim = when (result) {
        is RecoveryClaimResult.Acquired -> result.claim
        RecoveryClaimResult.Busy -> error("Initialization/snapshot poisoned a healthy coordination claim")
        is RecoveryClaimResult.Unavailable -> error("Coordination unavailable: ${result.reason}")
    }

    private fun <T> call(peer: IBinder, nonce: String, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(RecoveryQualificationPeer.DESCRIPTOR)
            input.writeString(nonce)
            input.write()
            check(peer.transact(code, input, output, 0)) { "Qualification transaction refused" }
            output.readException()
            return output.read()
        } finally { input.recycle(); output.recycle() }
    }

    /** Independent test oracle, never overlapping a production channel in this process. */
    private class KernelReleaseWitness(private val file: File) {
        private val exited = CountDownLatch(1)
        private val closed = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread({
            var channel: FileChannel? = null
            var normal = false
            try {
                channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                val lock = channel.lock()
                lock.release()
                normal = true
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                var confirmed = false
                try {
                    channel?.close()
                    confirmed = channel != null
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    // Interrupted/error paths remain conservative even if a later close was a no-op.
                    closed.set(normal && confirmed && failure.get() == null)
                    exited.countDown()
                }
            }
        }, "recovery-kernel-release-witness").apply { isDaemon = true }

        fun start() = worker.start()

        fun awaitSuccess() {
            assertTrue("Kernel lock witness did not finish", exited.await(15, TimeUnit.SECONDS))
            worker.join(1_000)
            assertFalse("Kernel witness has not actually exited", worker.isAlive)
            failure.get()?.let { throw AssertionError("Kernel witness failed", it) }
            assertTrue("Kernel witness channel close is unconfirmed", closed.get())
        }

        fun stopAndConfirm(): Boolean {
            if (worker.isAlive) worker.interrupt()
            if (!exited.await(10, TimeUnit.SECONDS)) return false
            worker.join(1_000)
            return !worker.isAlive && closed.get()
        }
    }
}
