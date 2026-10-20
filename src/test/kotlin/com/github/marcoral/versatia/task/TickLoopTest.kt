package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.testsupport.FakeScheduler
import com.github.marcoral.versatia.testsupport.captureLog
import com.github.marcoral.versatia.testsupport.fakePlugin
import com.github.marcoral.versatia.testsupport.heartbeatUntil
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** The TickLoop behind every PluginScope, driven by hand on the test thread as the server thread; see docs/runtime.md. */
class TickLoopTest {
    private val core = fakePlugin("TickCore")
    private val ticker = ServerTicker(core)
    private val loop = ticker.loop
    private val alpha = fakePlugin("TickAlpha")
    private val scope = PluginScopeBean(alpha, ticker)
    private val log = alpha.captureLog()

    @AfterTest
    fun tearDown() {
        log.close()
        ticker.close()
    }

    private fun heartbeats(times: Int) = repeat(times) { loop.heartbeat() }

    @Test
    fun `delayTicks resumes at the n-th heartbeat, timers due together in the order they were registered`() {
        val resumed = mutableListOf<Pair<String, Long>>()
        scope.launch { delayTicks(3); resumed += "a" to loop.tick }
        scope.launch { delayTicks(3); resumed += "b" to loop.tick }
        loop.heartbeat()
        scope.launch { delayTicks(2); resumed += "c" to loop.tick }
        loop.heartbeat()
        assertEquals(emptyList(), resumed)

        loop.heartbeat()
        assertEquals(listOf("a" to 3L, "b" to 3L, "c" to 3L), resumed)
    }

    @Test
    fun `delayTicks of zero waits for the next heartbeat and never resumes in place`() {
        val steps = mutableListOf<String>()
        scope.launch {
            steps += "before"
            delayTicks(0)
            steps += "after at ${loop.tick}"
        }
        assertEquals(listOf("before"), steps)

        loop.heartbeat()
        assertEquals(listOf("before", "after at 1"), steps)
    }

    @Test
    fun `a cancelled timer never resumes`() {
        var resumed = false
        val job = scope.launch {
            delayTicks(2)
            resumed = true
        }
        job.cancel()
        heartbeats(3)

        assertFalse(resumed)
        assertTrue(job.isCancelled && job.isCompleted)
    }

    @Test
    fun `a disabled plugin's far-off timer no longer holds its coroutine`() {
        val held = launchHolding()
        loop.heartbeat()
        scope.stop()
        loop.heartbeat()

        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (held.get() != null && !deadline.hasPassedNow()) System.gc()
        assertNull(held.get(), "the timer of a cancelled coroutine still references what the coroutine captured")
    }

    /** A coroutine waiting a long time that captures an object; only a weak reference to it leaves this frame. */
    private fun launchHolding(): WeakReference<Any> {
        val captured = Any()
        scope.launch {
            delayTicks(1_000_000)
            println(captured)
        }
        return WeakReference(captured)
    }

    @Test
    fun `a timer registered from another thread is due the given ticks after the tick it was registered at`() {
        heartbeats(2)
        var resumedAt: Long? = null
        // Unconfined: registered on the other thread, resumed in place by the heartbeat firing the timer
        thread { scope.launch(Dispatchers.Unconfined) { delayTicks(3); resumedAt = loop.tick } }.join()

        heartbeats(2)
        assertNull(resumedAt)
        loop.heartbeat()
        assertEquals(5L, resumedAt)
    }

    @Test
    fun `a coroutine yielding in a loop advances one step per heartbeat`() {
        var steps = 0
        val job = scope.launch {
            while (true) {
                steps++
                yield()
            }
        }
        assertEquals(1, steps)

        loop.heartbeat()
        assertEquals(2, steps)
        loop.heartbeat()
        assertEquals(3, steps)
        job.cancel()
    }

    @Test
    fun `a heartbeat runs the dispatched blocks, then the posted rechecks, then the due timers`() {
        val order = mutableListOf<String>()
        val lane = loop.lane(alpha, Job())
        scope.launch { delayTick(); order += "timer" }
        loop.post(lane) { order += "recheck" }
        loop.submit(lane, EmptyCoroutineContext) { order += "block" }

        loop.heartbeat()
        assertEquals(listOf("block", "recheck", "timer"), order)
    }

    @Test
    fun `a throwing block or recheck is logged and the heartbeat goes on`() {
        val ran = mutableListOf<String>()
        val lane = loop.lane(alpha, Job())
        loop.submit(lane, EmptyCoroutineContext) { throw IllegalStateException("block") }
        loop.post(lane) { throw IllegalStateException("recheck") }
        loop.submit(lane, EmptyCoroutineContext) { ran += "block" }
        scope.launch { delayTick(); ran += "timer" }

        loop.heartbeat()
        assertEquals(listOf("block", "timer"), ran)
        val severe = log.records.filter { it.level == Level.SEVERE }
        assertEquals(List(2) { "A coroutine threw while running on the server thread" }, severe.map { it.message })
        assertEquals(listOf("block", "recheck"), severe.map { it.thrown.message })
    }

    @Test
    fun `the dispatcher runs in place on the server thread only`() {
        assertFalse(scope.serverThread.isDispatchNeeded(EmptyCoroutineContext))
        var elsewhere = false
        thread { elsewhere = scope.serverThread.isDispatchNeeded(EmptyCoroutineContext) }.join()
        assertTrue(elsewhere)
    }

    @Test
    fun `stop cancels every lane and finishes what it can before returning`() {
        val finished = mutableListOf<String>()
        val job = scope.launch {
            try {
                delayTicks(100)
            } finally {
                finished += "finally"
            }
        }

        assertEquals(emptyList(), loop.stop())
        assertTrue(loop.stopped)
        assertTrue(job.isCancelled)
        assertEquals(listOf("finally"), finished)
    }

    @Test
    fun `stop hands the queued blocks and every later one to Dispatchers IO with their job cancelled`() {
        val idle = loop.lane(alpha, Job().apply { complete() })
        val queuedJob = Job()
        val queued = CompletableFuture<Pair<String, Boolean>>()
        loop.submit(idle, queuedJob) { queued.complete(Thread.currentThread().name to queuedJob.isCancelled) }

        loop.stop()
        val (queuedThread, queuedCancelled) = queued.get(10, TimeUnit.SECONDS)
        assertContains(queuedThread, "DefaultDispatcher")
        assertTrue(queuedCancelled)

        val laterJob = Job()
        val later = CompletableFuture<Boolean>()
        var failure: Throwable? = null
        thread {
            try {
                loop.submit(idle, laterJob) { later.complete(laterJob.isCancelled) }
            } catch (e: Throwable) {
                failure = e
            }
        }.join()
        assertNull(failure, "submit never throws")
        assertTrue(later.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `the ServerTicker drives the loop from one Bukkit timer and cancels it when it closes`() {
        FakeScheduler.install()
        ticker.start()
        val timer = FakeScheduler.timersOf(core).single()
        assertEquals(0L to 1L, timer.delayTicks to timer.periodTicks)

        timer.task.run()
        assertEquals(1L, loop.tick)
        ticker.close()
        assertTrue(timer.cancelled)
        assertTrue(loop.stopped)
    }

    @Test
    fun `the ServerTicker warns about coroutines still running when VersatiaCore stops`() {
        loop.closeTimeout = 100.milliseconds
        val release = CountDownLatch(1)
        val stuck = scope.launch(CoroutineName("stuck")) { withContext(NonCancellable + Dispatchers.IO) { release.await() } }
        val coreLog = core.captureLog()

        ticker.close()
        coreLog.close()
        assertEquals(
            listOf("Coroutines of TickAlpha were still running when VersatiaCore stopped; they are cancelled and finish on a kotlinx thread"),
            coreLog.messages(Level.WARNING),
        )
        release.countDown()
        loop.heartbeatUntil { stuck.isCompleted }
    }
}
