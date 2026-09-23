package com.envi.wispr.ui

import androidx.work.WorkInfo
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelUiAction
import com.envi.wispr.models.ModelUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * `ModelWorkViewModel` (#255): the settings shell's one owner of model-delivery observation. Main is one named
 * thread (`test-main`) and IO another (`test-io`), so a row can say where the projection ran; every wait is on a
 * signal or a drained executor, never a clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelWorkViewModelTest {
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-main") }
    private val mainThread: Thread = mainExecutor.submit<Thread> { Thread.currentThread() }.get()

    /**
     * Main as production's `Main.immediate` behaves: work started ON main runs at once, work from elsewhere is
     * queued. So `show`'s activation subscribes inside the `show` call, and a row can count it without hops.
     */
    private val mainDispatcher = object : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread() !== mainThread
        override fun dispatch(context: CoroutineContext, block: Runnable) = mainExecutor.execute(block)
    }
    private val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-io") }

    /** One replaying stream per unique work name; each actual subscription is counted by name. */
    private val streams = ConcurrentHashMap<String, MutableSharedFlow<List<WorkInfo>>>()
    private val subscriptions = ConcurrentHashMap<String, AtomicInteger>()
    private fun stream(name: String) = streams.getOrPut(name) { MutableSharedFlow(replay = 1) }
    private fun subscribed(name: String) = subscriptions[name]?.get() ?: 0
    private val work: (String) -> Flow<List<WorkInfo>> = { name ->
        flow {
            subscriptions.getOrPut(name) { AtomicInteger() }.incrementAndGet()
            stream(name).collect { emit(it) }
        }
    }

    private val readiness = MutableStateFlow(ReadinessUiState(loaded = true, readiness = AppReadiness(speechModelReady = true, polishModelReady = false)))

    /** Every projection: the model, the chosen work's state, readiness, and the thread it ran on. */
    private val projected = CopyOnWriteArrayList<String>()
    @Volatile private var holdS1: CountDownLatch? = null
    private val s1Entered = CountDownLatch(1)
    private val project: (WorkInfo?, Boolean, ModelDescriptor) -> ModelUiState = { info, ready, model ->
        val name = if (model == ModelManifest.s1) "s1" else "parakeet"
        projected += "$name:${info?.state}:$ready on ${Thread.currentThread().name.substringBefore(" @")}"
        if (name == "s1") {
            s1Entered.countDown()
            holdS1?.await(10, TimeUnit.SECONDS)
        }
        ModelUiState("$name ${info?.state} $ready", ModelHealth.READY, action = ModelUiAction.REMOVE)
    }

    private fun info(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state, emptySet())

    private fun viewModel() = ModelWorkViewModel(work, readiness, project, ioExecutor.asCoroutineDispatcher())

    private fun onMain(block: () -> Unit) = mainExecutor.submit(block).get(10, TimeUnit.SECONDS)

    private fun awaitModels(vm: ModelWorkViewModel, predicate: (ModelWorkUiState) -> Boolean): ModelWorkUiState = runBlocking {
        withTimeout(10_000) { vm.models.first(predicate) }
    }

    private val s1Download = ModelDeliveryWorker.downloadWorkName(ModelManifest.s1)
    private val s1Adoption = ModelDeliveryWorker.adoptionWorkName(ModelManifest.s1)
    private val parakeetDownload = ModelDeliveryWorker.downloadWorkName(ModelManifest.parakeet)
    private val parakeetAdoption = ModelDeliveryWorker.adoptionWorkName(ModelManifest.parakeet)

    @Before fun setUp() = Dispatchers.setMain(mainDispatcher)

    @After fun tearDown() {
        holdS1?.countDown()
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    private fun seed(vararg names: String, state: WorkInfo.State = WorkInfo.State.RUNNING) {
        names.forEach { check(stream(it).tryEmit(listOf(info(state)))) }
    }

    /** Row 1: nothing shown, nothing observed or projected. MUTATION: ignore visibility. */
    @Test fun aHiddenModelIsNeitherObservedNorProjected() {
        seed(s1Download, s1Adoption, parakeetDownload, parakeetAdoption)
        val vm = viewModel()
        onMain { vm.show(null) }
        onMain { vm.show(AppDestination.History) }
        onMain { }
        ioExecutor.submit { }.get(10, TimeUnit.SECONDS)
        onMain { }
        assertEquals("no work subscribed: $subscriptions", 0, subscriptions.values.sumOf { it.get() })
        assertTrue("nothing projected: $projected", projected.isEmpty())
        assertEquals(ModelWorkUiState(), vm.models.value)
    }

    /** Row 2: AI Polish projects S1 with S1 readiness, and only S1. MUTATION: project Parakeet for Polish. */
    @Test fun aiPolishProjectsS1Only() {
        seed(s1Download, s1Adoption, parakeetDownload, parakeetAdoption)
        val vm = viewModel()
        onMain { vm.show(AppDestination.Polish) }
        val shown = awaitModels(vm) { it.polish != ModelWorkViewModel.CHECKING }
        assertEquals("s1 RUNNING false", shown.polish.label)
        assertEquals(ModelWorkViewModel.CHECKING, shown.speech)
        assertEquals(0, subscribed(parakeetDownload) + subscribed(parakeetAdoption))
    }

    /** Row 3: an active adoption beats a finished download. MUTATION: pass the download only. */
    @Test fun anActiveAdoptionBeatsAFinishedDownload() {
        check(stream(s1Download).tryEmit(listOf(info(WorkInfo.State.SUCCEEDED))))
        check(stream(s1Adoption).tryEmit(listOf(info(WorkInfo.State.RUNNING))))
        val vm = viewModel()
        onMain { vm.show(AppDestination.Polish) }
        assertEquals("s1 RUNNING false", awaitModels(vm) { it.polish != ModelWorkViewModel.CHECKING }.polish.label)
    }

    /** Row 4: the projection (a storage read) runs on IO, never on main. MUTATION: drop the `withContext(io)`. */
    @Test fun theProjectionRunsOffMain() {
        seed(parakeetDownload, parakeetAdoption)
        val vm = viewModel()
        onMain { vm.show(AppDestination.Transcription) }
        awaitModels(vm) { it.speech != ModelWorkViewModel.CHECKING }
        assertTrue("projected on IO: $projected", projected.isNotEmpty() && projected.all { it.endsWith("on test-io") })
    }

    /**
     * Row 5: a model hidden while its work changes starts at Checking on its next activation, then shows the NEW
     * work, from a fresh subscription. MUTATION: keep the last value across activations.
     */
    @Test fun aReturningTabStartsAtCheckingThenShowsTheNewWork() {
        seed(s1Download, s1Adoption)
        val vm = viewModel()
        onMain { vm.show(AppDestination.Polish) }
        awaitModels(vm) { it.polish.label == "s1 RUNNING false" }
        onMain { vm.show(null) }
        check(stream(s1Download).tryEmit(listOf(info(WorkInfo.State.SUCCEEDED))))
        check(stream(s1Adoption).tryEmit(listOf(info(WorkInfo.State.SUCCEEDED))))
        readiness.value = readiness.value.copy(readiness = readiness.value.readiness.copy(polishModelReady = true))
        var firstFrame: ModelWorkUiState? = null
        onMain {
            vm.show(AppDestination.Polish)
            firstFrame = vm.models.value
        }
        assertEquals("the first frame is Checking with no action", ModelWorkViewModel.CHECKING, firstFrame?.polish)
        assertEquals(ModelUiAction.NONE, firstFrame?.polish?.action)
        assertEquals("s1 SUCCEEDED true", awaitModels(vm) { it.polish != ModelWorkViewModel.CHECKING }.polish.label)
        assertEquals("a fresh subscription", 2, subscribed(s1Download))
    }

    /** Row 5b: a projection still running when the tab changes never lands on the new tab. MUTATION: omit cancellation. */
    @Test fun aLateProjectionNeverLandsAfterTheTabChanged() {
        seed(s1Download, s1Adoption, parakeetDownload, parakeetAdoption)
        holdS1 = CountDownLatch(1)
        val vm = viewModel()
        onMain { vm.show(AppDestination.Polish) }
        assertTrue("the S1 projection started", s1Entered.await(10, TimeUnit.SECONDS))
        onMain { vm.show(AppDestination.Transcription) }
        holdS1?.countDown()
        awaitModels(vm) { it.speech != ModelWorkViewModel.CHECKING }
        // The held S1 projection has returned and anything it queued on main has run.
        ioExecutor.submit { }.get(10, TimeUnit.SECONDS)
        onMain { }
        assertEquals("the late S1 result did not land", ModelWorkViewModel.CHECKING, vm.models.value.polish)
    }

    /** Row 6: one refresh per new finished snapshot; the same snapshot twice gives one. MUTATION: drop `distinctUntilChanged`. */
    @Test fun oneRefreshPerNewFinishedSnapshot() = runTest {
        listOf(s1Download, s1Adoption, parakeetDownload, parakeetAdoption).forEach { check(stream(it).tryEmit(emptyList())) }
        val vm = viewModel()
        var signals = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.finished.collect { signals++ } }
        runCurrent()
        val done = listOf(info(WorkInfo.State.SUCCEEDED))
        stream(parakeetDownload).emit(done)
        runCurrent()
        assertEquals(1, signals)
        stream(parakeetDownload).emit(done)
        runCurrent()
        assertEquals("the same snapshot again signals nothing", 1, signals)
        stream(s1Download).emit(listOf(info(WorkInfo.State.SUCCEEDED)))
        runCurrent()
        assertEquals("a second finish signals again", 2, signals)
    }

    /** Row 6d: a finish later in a work chain signals, not only its first item. MUTATION: read only the first item. */
    @Test fun aFinishLaterInAChainSignals() = runTest {
        listOf(s1Download, s1Adoption, parakeetDownload, parakeetAdoption).forEach { check(stream(it).tryEmit(emptyList())) }
        val vm = viewModel()
        var signals = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.finished.collect { signals++ } }
        runCurrent()
        val running = info(WorkInfo.State.RUNNING)
        stream(s1Download).emit(listOf(running))
        runCurrent()
        assertEquals("a running chain signals nothing", 0, signals)
        val chain = listOf(running, info(WorkInfo.State.SUCCEEDED))
        stream(s1Download).emit(chain)
        runCurrent()
        assertEquals("the second item's finish signals", 1, signals)
        stream(s1Download).emit(chain)
        runCurrent()
        assertEquals("the same chain again signals nothing", 1, signals)
    }

    /** Row 6b: a failed observation shows Checking; the next activation projects again. MUTATION: drop the `catch`. */
    @Test fun aFailedObservationShowsCheckingAndTheNextActivationRetries() {
        seed(s1Adoption)
        val failing = MutableStateFlow(true)
        val once: (String) -> Flow<List<WorkInfo>> = { name ->
            if (name == s1Download && failing.value) {
                flow {
                    emit(listOf(info(WorkInfo.State.RUNNING)))
                    stream("gate").first()
                    throw IllegalStateException("work observation failed")
                }
            } else {
                work(name)
            }
        }
        val vm = ModelWorkViewModel(once, readiness, project, ioExecutor.asCoroutineDispatcher())
        onMain { vm.show(AppDestination.Polish) }
        awaitModels(vm) { it.polish.label == "s1 RUNNING false" }
        check(stream("gate").tryEmit(emptyList()))
        assertEquals("Checking after the failure", ModelWorkViewModel.CHECKING, awaitModels(vm) { it.polish == ModelWorkViewModel.CHECKING }.polish)
        failing.value = false
        seed(s1Download, s1Adoption, state = WorkInfo.State.SUCCEEDED)
        onMain { vm.show(null) }
        onMain { vm.show(AppDestination.Polish) }
        assertEquals("s1 SUCCEEDED false", awaitModels(vm) { it.polish != ModelWorkViewModel.CHECKING }.polish.label)
    }

    /** Row 6c: a failed refresh watcher refreshes once and stops; the next start watches again. MUTATION: drop its refresh. */
    @Test fun aFailedRefreshWatcherRefreshesOnceAndTheNextStartWatchesAgain() = runBlocking {
        var refreshes = 0
        collectModelRefresh(flow { emit(Unit); throw IllegalStateException("watch failed") }) { refreshes++ }
        assertEquals("one for the signal, one for the failure", 2, refreshes)
        collectModelRefresh(flowOf(Unit)) { refreshes++ }
        assertEquals("the next start signals a later finish", 3, refreshes)
    }
}
