package dev.versatia.task

import dev.versatia.PluginEnhancer
import dev.versatia.PluginStates
import dev.versatia.ProcessorLoader
import dev.versatia.di.BeanProcessors
import dev.versatia.testsupport.FakeCore
import dev.versatia.testsupport.Recorder
import dev.versatia.testsupport.captureLog
import dev.versatia.testsupport.descriptor
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.heartbeatUntil
import dev.versatia.testsupport.testContext
import java.util.concurrent.CountDownLatch
import java.util.logging.Level
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PluginScope VersatiaCore gives every plugin, run on a TickLoop driven by hand on the test thread; see docs/runtime.md. */
class PluginScopeBeanTest {
    private val ticker = ServerTicker(fakePlugin("ScopeCore"))
    private val loop = ticker.loop
    private val alpha = fakePlugin("ScopeAlpha")
    private val scope = PluginScopeBean(alpha, ticker)
    private val log = alpha.captureLog()
    private val serverThread = Thread.currentThread()

    @AfterTest
    fun tearDown() {
        log.close()
        ticker.close()
    }

    @Test
    fun `a launch on the server thread runs at once up to the first suspension`() {
        val steps = mutableListOf<String>()
        scope.launch {
            steps += "started"
            delayTick()
            steps += "resumed"
        }
        assertEquals(listOf("started"), steps)

        loop.heartbeat()
        assertEquals(listOf("started", "resumed"), steps)
    }

    @Test
    fun `a launch from another thread starts at the next heartbeat on the server thread`() {
        var ranOn: Thread? = null
        thread { scope.launch { ranOn = Thread.currentThread() } }.join()
        assertNull(ranOn)

        loop.heartbeat()
        assertSame(serverThread, ranOn)
    }

    @Test
    fun `withContext of Dispatchers IO returns to the server thread at a heartbeat`() {
        var ioThread: Thread? = null
        var back: Thread? = null
        scope.launch {
            ioThread = withContext(Dispatchers.IO) { Thread.currentThread() }
            back = Thread.currentThread()
        }

        loop.heartbeatUntil { back != null }
        assertNotSame(serverThread, ioThread)
        assertSame(serverThread, back)
    }

    @Test
    fun `a failing coroutine is logged by its name and its siblings go on`() {
        var siblingSteps = 0
        scope.launch(CoroutineName("x")) {
            delayTick()
            throw IllegalStateException("named")
        }
        scope.launch { throw IllegalArgumentException("unnamed") }
        scope.launch {
            repeat(3) {
                delayTick()
                siblingSteps++
            }
        }

        repeat(3) { loop.heartbeat() }
        assertEquals(3, siblingSteps)
        assertTrue(scope.isActive)
        val severe = log.records.filter { it.level == Level.SEVERE }
        assertEquals(listOf("A coroutine failed", "Coroutine x failed"), severe.map { it.message })
        assertEquals(listOf("unnamed", "named"), severe.map { it.thrown.message })
    }

    @Test
    fun `stop runs the finally blocks before it returns`() {
        val finished = mutableListOf<String>()
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                finished += "waiting"
            }
        }
        scope.launch {
            try {
                delayTicks(100)
            } finally {
                finished += "counting"
            }
        }

        scope.stop()
        assertEquals(listOf("waiting", "counting"), finished)
        assertEquals(emptyList(), log.messages(Level.WARNING), "stopping is no misuse")
    }

    @Test
    fun `a tick wait during the disable is refused`() {
        var refusal: String? = null
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    try {
                        delayTick()
                    } catch (e: CancellationException) {
                        refusal = e.message
                    }
                }
            }
        }

        scope.stop()
        assertEquals("Plugin ScopeAlpha is being disabled; no tick passes for it any more", refusal)
    }

    @Test
    fun `stop waits within its timeout for a coroutine returning from Dispatchers IO`() {
        var slept = false
        var finishedOn: Thread? = null
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                // The cancelled caller gets CancellationException instead of the result, still on the server thread
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        Thread.sleep(50)
                        slept = true
                    }
                } finally {
                    finishedOn = Thread.currentThread()
                }
            }
        }

        scope.stop()
        assertTrue(slept)
        assertSame(serverThread, finishedOn)
        assertEquals(emptyList(), log.messages(Level.WARNING))
    }

    @Test
    fun `stop warns with the count and the names of the coroutines still running after its timeout`() {
        loop.closeTimeout = 100.milliseconds
        val release = CountDownLatch(1)
        val stuck = scope.launch(CoroutineName("stubborn")) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { release.await() }
            }
        }

        scope.stop()
        assertEquals(
            listOf("1 coroutines were still running 100ms after the plugin was disabled (stubborn); they are cancelled and finish when they next resume"),
            log.messages(Level.WARNING),
        )
        release.countDown()
        loop.heartbeatUntil { stuck.isCompleted }
    }

    @Test
    fun `a coroutine launched after stop never runs`() {
        scope.stop()
        var ran = false
        val job = scope.launch { ran = true }

        loop.heartbeat()
        assertFalse(ran)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling the scope from plugin code is reported as a misuse`() {
        scope.cancel()
        assertEquals(
            listOf("PluginScope was cancelled by plugin code, so nothing launched from it runs until the plugin restarts; cancel the jobs you launch, not the scope"),
            log.messages(Level.WARNING),
        )
    }

    @Test
    fun `every plugin injecting PluginScope receives a scope of its own`() {
        Recorder.reset()
        val states = PluginStates()
        val core = FakeCore(states)
        val enhancer = PluginEnhancer(ProcessorLoader())
        for (name in listOf("Alpha", "Beta")) {
            val elements = TaskElements("com.example.${name.lowercase()}.${name}Plugin")
            enhancer.enhance(descriptor(elements.pluginBean, elements.scopeAtStart, elements.bean(TaskElements.classTarget<ScopeHolder>(listOf(PluginScope::class)) { ScopeHolder(it[0] as PluginScope) })), testContext(name, states))
        }

        val alphaScope = states.of("Alpha")[PluginScopeBean.KEY]
        val betaScope = states.of("Beta")[PluginScopeBean.KEY]
        assertNotSame(alphaScope, betaScope)
        assertEquals(listOf<Any?>(alphaScope, betaScope), Recorder.events.map { (it.second as ScopeHolder).scope }, "a bean receives its plugin's scope")
        core.stop()
    }

    @Test
    fun `a prototype of a module made for a plugin receives that plugin's scope`() {
        Recorder.reset()
        val states = PluginStates()
        val core = FakeCore(states)
        val enhancer = PluginEnhancer(ProcessorLoader())
        val module = TaskElements("com.example.module.ModulePlugin")
        val prototype = module.bean(TaskElements.classTarget<ScopeHolder>(listOf(PluginScope::class)) { ScopeHolder(it[0] as PluginScope) })
            .copy(processorClass = BeanProcessors.EXPOSE_PROTOTYPE)
        enhancer.enhance(descriptor(module.pluginBean, module.scopeAtStart, prototype), testContext("Module", states))
        val consumer = TaskElements("com.example.consumer.ConsumerPlugin")
        val user = consumer.bean(TaskElements.classTarget<HolderUser>(listOf(ScopeHolder::class)) { HolderUser(it[0] as ScopeHolder) })
        enhancer.enhance(descriptor(consumer.pluginBean, consumer.scopeAtStart, user), testContext("Consumer", states))

        val holders = Recorder.events.filter { it.first == "ScopeHolder" }.map { it.second as ScopeHolder }
        assertEquals(listOf<Any?>(states.of("Module")[PluginScopeBean.KEY], states.of("Consumer")[PluginScopeBean.KEY]), holders.map { it.scope })
        assertSame(holders[1], Recorder.instanceOf("HolderUser"))
        core.stop()
    }
}

class HolderUser(holder: ScopeHolder) {
    init {
        Recorder.record("HolderUser", holder)
    }
}

class ScopeHolder(val scope: PluginScope) {
    init {
        Recorder.record("ScopeHolder", this)
    }
}
