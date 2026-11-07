package com.envi.wispr.recovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.Binder
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.asr.IAsrCallback
import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.asr.ParakeetEngine
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.models.EngineBindings
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.ui.RecoverySpeechClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.history.RecoverySaveOutcome
import com.envi.wispr.history.TranscriptRepository
import com.envi.wispr.models.ModelBootstrapApplication
import com.envi.wispr.ui.SettingsActivity
import com.envi.wispr.insertion.InsertionResults
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
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
 * Only disposable lab apps. Case e uses the actual speech model with a committed file fixture;
 * other cases do not use a model. No microphone, editor or complete recovery UAT claim.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecoveryStorageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.noBackupFilesDir, "protected-recovery.v1")
    private enum class WitnessState { NOT_REQUESTED, UNCONFIRMED, CLOSED }

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
        var witnessState = WitnessState.NOT_REQUESTED
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
            stoppingPeer.set(true)
            try {
                executor.submit {
                    call(remote, nonce, RecoveryQualificationPeer.KILL_SELF, { writeInt(peerPid) }) { Unit }
                }.get(10, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.ExecutionException) {
                // Binder death is expected; the kernel lock witness below supplies release proof.
            }
            assertTrue("Creator peer did not die", died.await(10, TimeUnit.SECONDS))
            witnessState = WitnessState.UNCONFIRMED
            remoteWitness(0, original.slot, setOf(peerPid))
            witnessState = WitnessState.CLOSED
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
            val canRetireOriginal = witnessState != WitnessState.UNCONFIRMED
            attempt { assertTrue("Remote witness unconfirmed; preserve disposable MAIN0 state", canRetireOriginal) }
            attempt { if (canRetireOriginal) replacement?.let { assertEquals(RecoveryReservationClose.RELEASED, it.closeOnWorker()) } }
            attempt { second?.let { assertEquals(RecoveryReservationClose.RELEASED, it.closeOnWorker()) } }
            val store = RecoveryAudioStore(context)
            listOfNotNull(old, second?.ref, replacement?.ref).forEach { ref ->
                if (!canRetireOriginal && ref.slot == 0) return@forEach
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
            attempt { if (canRetireOriginal) assertEquals(listOf(null, null), ready(store.snapshotOnWorker()).ledger.slots) }
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

    @Test fun dEncryptedWriterDrainsItsTailAndTornSuffixKeepsTheEarlierAudio() {
        assertNondebug()
        val store = RecoveryAudioStore(context)
        val reservation = ready(store.reserveOnWorker(RecoveryQualificationPeer.fixture(UUID.randomUUID())))
        var writer: RecoveryAudioWriter? = null
        var bodyFailure: Throwable? = null
        try {
            ready(reservation.prepareOnWorker())
            val activeWriter = checkNotNull(RecoveryAudioWriter.startOnWorker(ready(store.openAudioWriterOnWorker(reservation.ref))))
            writer = activeWriter
            val block = ByteArray(1_024) { 42 }
            repeat(32) { activeWriter.offer(block, block.size, it * 1_024L) }
            activeWriter.offer(byteArrayOf(1, 0, 2, 0), 4, 32_768L)
            activeWriter.finishOnCaptureClosed(32_772L, true)
            val closed = activeWriter.completion.get(15, TimeUnit.SECONDS)
            assertEquals(RecoveryClaimRelease.RELEASED, closed.released)
            assertEquals(16_386, closed.sampleCount)
            assertEquals(0L, closed.firstSample)
            assertTrue(closed.completedCapture)
            assertFalse(closed.failed)

            val file = File(root, "slot-${reservation.ref.slot}.audio")
            val key = keyStore().getKey(alias(reservation.ref), null) as SecretKey
            fun prefix() = Files.newInputStream(file.toPath()).use {
                RecoveryAudioPrefix.read(it, reservation.ref.identity, key) {}
            }
            fun digest(prefix: RecoveryPcmPrefix): String = java.security.MessageDigest.getInstance("SHA-256")
                .digest(prefix.pcm.copyOf(prefix.byteCount)).joinToString("") { "%02x".format(it) }

            prefix().use {
                assertEquals(32_772, it.byteCount)
                // Independently computed from 32768 literal 0x2a bytes, then 01 00 02 00.
                assertEquals("808d45051b09de1782d0c0232305dff52684dd887a1bccbfcbd4face4b443541", digest(it))
                assertTrue(it.completedCapture)
            }
            // A crash during the footer cannot erase either already-authenticated audio frame.
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.truncate(it.size() - 5L) }
            prefix().use {
                assertEquals(32_772, it.byteCount)
                assertFalse(it.completedCapture)
            }
            // A crash during the second audio frame retains the first full frame, without a gap.
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.truncate(32_768L + RecoveryFrameLimits.OVERHEAD_BYTES + 10L) }
            prefix().use {
                assertEquals(32_768, it.byteCount)
                assertEquals("e1319fe98edde6e1fb2bed9422ee3b250239b1d8ae1fa88d0f8c10b7cdb81236", digest(it))
                assertTrue(it.partial)
            }
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            val failures = ArrayList<Throwable>()
            fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures.add(failure) } }
            attempt {
                writer?.let {
                    it.finishOnCaptureClosed(0L, false)
                    assertEquals(RecoveryClaimRelease.RELEASED, it.completion.get(15, TimeUnit.SECONDS).released)
                }
            }
            attempt { assertEquals(RecoveryReservationClose.RELEASED, reservation.closeOnWorker()) }
            attempt { ready(store.markTerminalOnWorker(reservation.ref, RecoveryDisposition.NO_REPLAY)) }
            attempt { ready(store.cleanupOnWorker(reservation.ref)) }
            if (failures.isNotEmpty()) {
                val original = bodyFailure
                if (original != null) failures.forEach(original::addSuppressed)
                else throw AssertionError("Writer qualification cleanup failed").also { error -> failures.forEach(error::addSuppressed) }
            }
        }
    }

    @Test fun eOneSpentReplayReturnsActualSpeechAndRefusesADuplicateAfterReaderRelease() {
        assertNondebug()
        val descriptor = checkNotNull(ModelStorage.usable(context, ModelManifest.parakeet)) {
            "Prerequisite: stage the verified speech model on this disposable app before case e"
        }
        val pcm = InstrumentationRegistry.getInstrumentation().context.assets.open("recovery-quarterly.pcm").use { it.readBytes() }
        assertEquals("c4ff9f5dd1b604d0271cb5d9c3837640de0983b34df266266a5efba717d7300f",
            java.security.MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) })
        val store = RecoveryAudioStore(context)
        val frozen = FrozenRecoveryContext.freeze(UUID.randomUUID(), 123L, ParakeetEngine.RECIPE_ID,
            "android-pcm-s16le-16000-mono-v1", CleanupOptions(), emptyList())
        val reservation = ready(store.reserveOnWorker(frozen))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gotPeer = CountDownLatch(1)
        val peer = AtomicReference<IAsrService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { peer.set(IAsrService.Stub.asInterface(service)); gotPeer.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var writer: RecoveryAudioStore.AudioWriterLease? = null
        var replayLease: RecoveryAudioStore.ReplayLease? = null
        var client: RecoverySpeechClient? = null
        var witnessState = WitnessState.NOT_REQUESTED
        val drained = CountDownLatch(1)
        var bodyFailure: Throwable? = null
        try {
            ready(reservation.prepareOnWorker())
            val active = ready(store.openAudioWriterOnWorker(reservation.ref))
            writer = active
            var offset = 0
            while (offset < pcm.size) {
                val size = minOf(RecoveryFrameLimits.PAYLOAD_BYTES, pcm.size - offset)
                active.appendOnWorker(17L + offset / 2, pcm.copyOfRange(offset, offset + size))
                offset += size
            }
            assertTrue(active.completeOnWorker(17L + pcm.size / 2))
            assertTrue("a live main claim is not an orphan", store.sealOrphanOnWorker(reservation.ref) is RecoveryStoreResult.Busy)
            assertEquals(RecoveryReservationClose.RELEASED, reservation.closeOnWorker())
            assertTrue("the actual writer still owns its audio", store.sealOrphanOnWorker(reservation.ref) is RecoveryStoreResult.Busy)
            assertEquals(RecoveryClaimRelease.RELEASED, active.closeOnWorker())
            val lease = ready(store.sealOrphanOnWorker(reservation.ref))
            replayLease = lease
            assertEquals(frozen.takeId, lease.contextOnWorker().takeId)
            val authorization = ready(lease.reserveAttemptOnWorker())
            assertTrue("a reservation cannot be renewed", lease.reserveAttemptOnWorker() is RecoveryStoreResult.Unavailable)

            val result = AtomicReference<RecoveryResult?>()
            val answered = CountDownLatch(1)
            val probeBound = AtomicBoolean()
            val clientStarted = AtomicBoolean()
            val mainFailure = AtomicReference<Throwable?>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try {
                    probeBound.set(EngineBindings.bind(context, EngineBindings.Engine.SPEECH, connection, Context.BIND_AUTO_CREATE))
                    if (probeBound.get()) {
                        client = RecoverySpeechClient(context, authorization, scope,
                            result = { result.set(it); answered.countDown() }, drained = { drained.countDown() })
                        clientStarted.set(checkNotNull(client).start { true })
                    }
                } catch (failure: Throwable) { mainFailure.set(failure) }
            }
            mainFailure.get()?.let { throw AssertionError("Main-thread replay binding failed", it) }
            assertTrue("actual ASR probe binding was refused", probeBound.get())
            assertTrue("actual recovery client was not admitted", clientStarted.get())
            assertTrue("actual ASR binding did not connect", gotPeer.await(15, TimeUnit.SECONDS))
            assertTrue("actual encrypted-input speech request did not answer", answered.await(90, TimeUnit.SECONDS))
            val actual = checkNotNull(result.get()) { "Actual recovery returned unavailable" }
            assertEquals("The quarterly marker sentence lands tomorrow.", actual.rawText)
            assertEquals(17L, actual.firstSample)
            assertEquals(pcm.size / 2L, actual.sampleCount)
            assertTrue(actual.completedCapture)
            assertTrue(actual.partial)
            assertEquals(descriptor.engineId, actual.model.engineId)
            assertEquals(descriptor.id, actual.model.modelId)
            assertEquals(descriptor.pinnedRevision, actual.model.pinnedRevision)
            assertEquals(ParakeetEngine.RECIPE_ID, actual.model.recipeId)
            assertEquals(descriptor.displayName, actual.model.displayName)

            // A result is not retirement. The separate audio peer owns the only raw witness channel.
            val asrProcesses = context.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses.orEmpty()
                .filter { it.uid == Process.myUid() && it.processName == context.packageName + ":asr" }
            assertEquals("Identify exactly one actual ASR reader process", 1, asrProcesses.size)
            val asrPid = asrProcesses.single().pid
            witnessState = WitnessState.UNCONFIRMED
            val witnessPid = remoteWitness(1, reservation.ref.slot, setOf(asrPid))
            witnessState = WitnessState.CLOSED
            val duplicate = CountDownLatch(1)
            val duplicateResult = AtomicBoolean()
            val duplicateReason = java.util.concurrent.atomic.AtomicInteger(-1)
            val peerPid = java.util.concurrent.atomic.AtomicInteger(-1)
            checkNotNull(peer.get()).transcribeRecovery(reservation.ref.identity.takeId.toString(), reservation.ref.storeId.toString(),
                reservation.ref.slot, reservation.ref.identity.generation, authorization.attempt.toString(), reservation.ref.identity.context.copyBytes(),
                object : IAsrCallback.Stub() {
                    override fun onResult(text: String?) { duplicateResult.set(true); peerPid.set(Binder.getCallingPid()); duplicate.countDown() }
                    override fun onError(message: String?) { peerPid.set(Binder.getCallingPid()); duplicate.countDown() }
                    override fun onFailure(reason: Int, detail: String?) { duplicateReason.set(reason); peerPid.set(Binder.getCallingPid()); duplicate.countDown() }
                })
            assertTrue("duplicate submission did not settle", duplicate.await(30, TimeUnit.SECONDS))
            assertFalse("one nonce decoded a second time", duplicateResult.get())
            assertEquals(AsrFailureReason.AUDIO_UNREADABLE.code, duplicateReason.get())
            assertEquals("callback must come from the identified ASR process", asrPid, peerPid.get())
            assertNotEquals("witness must be a separate process from the reader", witnessPid, peerPid.get())
            assertNotEquals(Process.myPid(), peerPid.get())
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            val failures = ArrayList<Throwable>()
            fun attempt(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures.add(failure) } }
            attempt {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { client?.close(); EngineBindings.unbind(connection) }
                if (client != null) assertTrue("local recovery transport did not drain", drained.await(15, TimeUnit.SECONDS))
            }
            scope.cancel()
            attempt { writer?.let { assertEquals(RecoveryClaimRelease.RELEASED, it.closeOnWorker()) } }
            attempt { assertEquals(RecoveryReservationClose.RELEASED, reservation.closeOnWorker()) }
            attempt { replayLease?.let { assertEquals(RecoveryClaimRelease.RELEASED, it.closeOnWorker()) } }
            if (witnessState != WitnessState.UNCONFIRMED) {
                attempt { ready(store.markTerminalOnWorker(reservation.ref, RecoveryDisposition.NO_REPLAY)) }
                attempt { ready(store.cleanupOnWorker(reservation.ref)) }
            } // Unknown remote outcome keeps the failed disposable slot/key/files for inspection.
            if (failures.isNotEmpty()) {
                val original = bodyFailure
                if (original != null) failures.forEach(original::addSuppressed)
                else throw AssertionError("Replay qualification cleanup failed").also { error -> failures.forEach(error::addSuppressed) }
            }
        }
    }

    /** Product Outcome: the real foreground owner writes an orphan's words into real History once.
     * This stages encrypted file audio, not microphone input or an actual main-process crash. */
    @Test fun fForegroundRecoverySavesRealHistoryAndCommittedDeletionRejectsLateWords() {
        assertNondebug()
        checkNotNull(ModelStorage.usable(context, ModelManifest.parakeet))
        val pcm = InstrumentationRegistry.getInstrumentation().context.assets.open("recovery-quarterly.pcm").use { it.readBytes() }
        val store = RecoveryAudioStore(context)
        val take = UUID.randomUUID()
        val frozen = FrozenRecoveryContext.freeze(take, System.currentTimeMillis(), ParakeetEngine.RECIPE_ID,
            "android-pcm-s16le-16000-mono-v1", CleanupOptions(), emptyList())
        val reservation = ready(store.reserveOnWorker(frozen))
        var writer: RecoveryAudioStore.AudioWriterLease? = null
        val dao = EnviousWisprDatabase.get(context).transcriptDao()
        val repository = TranscriptRepository(dao)
        try {
            ready(reservation.prepareOnWorker())
            val active = ready(store.openAudioWriterOnWorker(reservation.ref))
            writer = active
            var offset = 0
            while (offset < pcm.size) {
                val size = minOf(RecoveryFrameLimits.PAYLOAD_BYTES, pcm.size - offset)
                active.appendOnWorker(offset / 2L, pcm.copyOfRange(offset, offset + size))
                offset += size
            }
            assertTrue(active.completeOnWorker(pcm.size / 2L))
            assertEquals(RecoveryClaimRelease.RELEASED, active.closeOnWorker())
            writer = null
            assertEquals(RecoveryReservationClose.RELEASED, reservation.closeOnWorker())
            ActivityScenario.launch(SettingsActivity::class.java).use {
                val row = runBlocking {
                    withTimeout(90_000) { dao.observeAll().first { rows -> rows.any { it.takeId == take.toString() } } }
                        .single { it.takeId == take.toString() }
                }
                assertEquals("The quarterly marker sentence lands tomorrow.", row.originalText)
                assertEquals(row.originalText, row.finalText)
                assertTrue(row.recovered)
                assertEquals(0L, row.recoveryStartSample)
                assertEquals(false, row.recoveryPartial)
                assertEquals(InsertionResults.HISTORY_ONLY, row.insertionResult)
                assertTrue(checkNotNull(row.recoveryModelIdentity).startsWith("EWR-MODEL-1"))
                runBlocking {
                    ModelBootstrapApplication.protectedHistoryDeletion(context).delete(row)
                    assertTrue(repository.isTakeDeleted(take.toString()))
                    assertNull(dao.findByTakeId(take.toString()))
                    assertEquals(RecoverySaveOutcome.Deleted, repository.saveRecovered(row.copy(id = 0)))
                    assertNull(dao.findByTakeId(take.toString()))
                }
            }
        } finally {
            writer?.let { assertEquals(RecoveryClaimRelease.RELEASED, it.closeOnWorker()) }
            assertEquals(RecoveryReservationClose.RELEASED, reservation.closeOnWorker())
            // Unknown/busy ownership is retained on a failed run; never remove raw files or lock inodes.
            if (store.markTerminalOnWorker(reservation.ref, RecoveryDisposition.DISCARDED) is RecoveryStoreResult.Ready) {
                store.cleanupOnWorker(reservation.ref)
            }
        }
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

    /** A fresh neutral private-peer binding witnesses acquisition and checked close in another process. */
    private fun remoteWitness(role: Int, slot: Int, forbiddenPids: Set<Int>): Int {
        val nonce = UUID.randomUUID().toString()
        val connected = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder.set(service); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "remote-kernel-witness").apply { isDaemon = true } }
        var bound = false
        try {
            bound = context.bindService(Intent(context, RecoveryQualificationService::class.java)
                .setIdentifier(nonce).putExtra("nonce", nonce), connection, Context.BIND_AUTO_CREATE)
            assertTrue("Private witness binding was refused", bound)
            assertTrue("Private witness did not connect", connected.await(15, TimeUnit.SECONDS))
            val peer = checkNotNull(binder.get())
            return executor.submit<Int> {
                val pid = call(peer, nonce, RecoveryQualificationPeer.HELLO, {}) {
                    check(readInt() == Process.myUid()) { "Witness UID mismatch" }
                    val actual = readInt()
                    check(actual > 0 && actual != Process.myPid() && actual !in forbiddenPids) { "Witness must be a separate actual process" }
                    check(readString() == context.packageName + ":audio") { "Unexpected witness process" }
                    actual
                }
                call(peer, nonce, RecoveryQualificationPeer.WITNESS_RELEASE, { writeInt(role); writeInt(slot) }) {
                    check(readInt() == 1) { "Witness acquisition/closure unconfirmed" }
                }
                pid
            }.get(20, TimeUnit.SECONDS)
        } finally {
            try { if (bound) context.unbindService(connection) }
            finally { executor.shutdown() } // Do not interrupt/pretend to retire a blocked RPC.
        }
    }
}
