package com.github.marcoral.versatia

import com.github.marcoral.versatia.di.ExposedBean
import com.github.marcoral.versatia.di.ExposedBeanRegistry
import com.github.marcoral.versatia.sample.Ticker
import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.fakePlugin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.bukkit.plugin.Plugin

/** The runtime over hand-written descriptors: the registry of enhanced plugins, friendly names and the reload event. */
class VersatiaRuntimeTest {
    private val states = PluginStates()
    private val runtime = VersatiaRuntime(states)

    private val tickerMain = "com.github.marcoral.versatia.sample.TickerPlugin"
    private val plainMain = "com.github.marcoral.versatia.engine.descriptor.sample.SamplePlugin"

    @BeforeTest
    fun setUp() {
        Recorder.reset()
        Ticker.onStart = {}
        Ticker.onTick = {}
    }

    @Test
    fun `registers plugins under their friendly name or their plugin name, in start order`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        val plain = fakePlugin("Plain", main = plainMain)

        runtime.enhance(ticker)
        runtime.enhance(plain)

        assertEquals(listOf("ticker", "Plain"), runtime.registry.friendlyNames.toList())
        assertSame(ticker, runtime.registry["ticker"])
        assertEquals("Plain", runtime.registry.friendlyNameOf(plain))
        assertNull(runtime.registry.friendlyNameOf(fakePlugin("Stranger", main = plainMain)))
    }

    @Test
    fun `reload runs the reload phases only and keeps the registration`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        runtime.enhance(ticker)
        assertEquals(listOf("Ticker.start", "Ticker.tick"), Recorder.names())

        Recorder.reset()
        runtime.reload(ticker)

        assertEquals(listOf("Ticker.tick"), Recorder.names())
        assertEquals(listOf("ticker"), runtime.registry.friendlyNames.toList())
    }

    @Test
    fun `disable runs the stop phase and forgets the plugin`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        runtime.enhance(ticker)
        Recorder.reset()

        runtime.disable(ticker)

        assertEquals(listOf("Ticker.stop"), Recorder.names())
        assertEquals(emptySet(), runtime.registry.friendlyNames)
    }

    @Test
    fun `rejects a friendly name another running plugin uses`() {
        runtime.enhance(fakePlugin("TickerPlugin", main = tickerMain))

        val error = assertFailsWith<EnhancementException> { runtime.enhance(fakePlugin("Clone", main = tickerMain)) }

        assertContains(error.message!!, "Plugin Clone: the friendly name \"ticker\" is already used by TickerPlugin")
    }

    @Test
    fun `a plugin whose start failed skips the stop phase when disabled and cannot be reloaded`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        Ticker.onStart = { error("start failed") }
        assertFailsWith<EnhancementException> { runtime.enhance(ticker) }

        runtime.disable(ticker)

        assertEquals(emptyList(), Recorder.names())
        assertEquals(emptySet(), runtime.registry.friendlyNames)
        assertEquals("Plugin TickerPlugin is not running", assertFailsWith<EnhancementException> { runtime.reload(ticker) }.message)
    }

    @Test
    fun `a plugin without a descriptor is disabled quietly`() {
        val ghost = fakePlugin("Ghost", main = "com.example.ghost.GhostPlugin")
        assertFailsWith<EnhancementException> { runtime.enhance(ghost) }

        runtime.disable(ghost)
    }

    @Test
    fun `a plugin that disables itself while starting is not registered and can start again`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        Ticker.onStart = { runtime.disable(ticker) }

        runtime.enhance(ticker)

        assertFalse("Ticker.stop" in Recorder.names())
        assertEquals(emptySet(), runtime.registry.friendlyNames)

        Ticker.onStart = {}
        runtime.enhance(ticker)
        assertSame(ticker, runtime.registry["ticker"])
    }

    @Test
    fun `a plugin that disables itself while starting runs none of its later start functions`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        Ticker.onStart = { runtime.disable(ticker) }

        runtime.enhance(ticker)

        assertEquals(listOf("Ticker.start"), Recorder.names())
    }

    @Test
    fun `a plugin that disables itself while reloading stops once and can start again`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        runtime.enhance(ticker)
        Recorder.reset()
        Ticker.onTick = { runtime.disable(ticker) }

        runtime.reload(ticker)

        assertEquals(listOf("Ticker.stop", "Ticker.tick"), Recorder.names())
        assertEquals(emptySet(), runtime.registry.friendlyNames)
        Ticker.onTick = {}
        runtime.enhance(ticker)
        assertSame(ticker, runtime.registry["ticker"])
    }

    @Test
    fun `a start failing after the plugin disabled itself keeps its exception when its state fails to close`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        Ticker.onStart = {
            val store = states.of(ticker.name)
            runtime.disable(ticker)
            store.getOrPut(StateKey("Test.leak")) { AutoCloseable { throw IllegalStateException("close failed") } }
            throw IllegalStateException("start failed")
        }

        val error = assertFailsWith<Exception> { runtime.enhance(ticker) }

        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it.message == "start failed" }, "the start failure is reported")
        assertEquals("close failed", error.suppressed.single().message)
    }

    /** Records that [consumer] holds a bean of [owner], as a bean session does when it injects one, and whether the bean follows [owner]'s reloads, as one with `@Value` properties does. */
    private fun share(owner: Plugin, consumer: Plugin, followsReloads: Boolean = true) {
        val beans = states.runtime.getOrPut(ExposedBeanRegistry.KEY) { ExposedBeanRegistry() }
        val className = "test.Bean${beans.size}"
        ExposedBean.Singleton(className, "bean", false, listOf(className), owner.name, Any()).also(beans::expose).obtain(consumer)
        if (followsReloads) beans.followOwnerReloads(owner.name, className)
    }

    @Test
    fun `a reload leaves alone the plugins holding only beans that do not follow it`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        val plain = fakePlugin("Plain", main = plainMain)
        runtime.enhance(ticker)
        runtime.enhance(plain)
        share(plain, ticker, followsReloads = false)
        Recorder.reset()

        val outcomes = runtime.reload(listOf(plain))

        assertEquals(listOf("Plain"), outcomes.map { it.name })
        assertEquals(emptyList<String>(), Recorder.names(), "ticker holds a bean of Plain, but not one that follows its reloads")
    }


    @Test
    fun `a reload goes on to the plugins holding beans of the reloaded one, once each, even in a cycle`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        val plain = fakePlugin("Plain", main = plainMain)
        runtime.enhance(ticker)
        runtime.enhance(plain)
        share(ticker, plain)
        share(plain, ticker)
        Recorder.reset()

        val outcomes = runtime.reload(listOf(ticker))

        assertEquals(listOf("ticker" to null, "Plain" to "ticker"), outcomes.map { it.name to it.cause })
        assertEquals(listOf("Ticker.tick"), Recorder.names(), "the way back to ticker does not reload it again")
        assertEquals("Reloaded ticker; then Plain, which uses beans of ticker.", ReloadOutcome.summary(outcomes))
    }

    @Test
    fun `a plugin that fails to reload after the plugin whose beans it uses is reported and undoes nothing`() {
        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        val plain = fakePlugin("Plain", main = plainMain)
        runtime.enhance(ticker)
        runtime.enhance(plain)
        share(plain, ticker)
        Ticker.onTick = { error("tick failed") }

        runtime.reload(plain)
        val outcomes = runtime.reload(listOf(plain))

        assertEquals(listOf(null, "tick failed"), outcomes.map { it.failure?.let { e -> generateSequence(e) { c -> c.cause }.last().message } })
        assertContains(outcomes[1].message!!, "ticker failed to reload after Plain, whose beans it uses: ")
        assertContains(outcomes[1].message!!, "Plain stays reloaded; fix ticker and reload it")
        assertEquals("Reloaded Plain.", ReloadOutcome.summary(outcomes))
    }

    @Test
    fun `refuses to start, reload or stop a plugin off the main thread`() {
        var mainThread = false
        val ticker = fakePlugin("TickerPlugin", main = tickerMain, primaryThread = { mainThread })
        fun message(action: String) = "Plugin TickerPlugin: $action must run on the server's main thread; schedule it with plugin.runTask { ... }"

        assertEquals(message("start"), assertFailsWith<EnhancementException> { runtime.enhance(ticker) }.message)
        assertNull(runtime.registry.friendlyNameOf(ticker))
        mainThread = true
        runtime.enhance(ticker)
        mainThread = false
        assertEquals(message("reload"), assertFailsWith<EnhancementException> { runtime.reload(ticker) }.message)
        assertEquals(message("reload"), assertFailsWith<EnhancementException> { runtime.reload(listOf(ticker)) }.message)
        assertEquals(message("stop"), assertFailsWith<EnhancementException> { runtime.disable(ticker) }.message)
        assertEquals(listOf("Ticker.start", "Ticker.tick"), Recorder.names(), "nothing ran off the main thread")

        mainThread = true
        runtime.disable(ticker)
        assertEquals("Ticker.stop", Recorder.names().last())
    }

    @Test
    fun `refuses a reload from inside the plugin's start or reload`() {

        val ticker = fakePlugin("TickerPlugin", main = tickerMain)
        Ticker.onStart = { runtime.reload(ticker) }
        val starting = assertFailsWith<EnhancementException> { runtime.enhance(ticker) }
        assertEquals("Plugin TickerPlugin is still starting; it can be reloaded once its start has completed", starting.message)

        runtime.disable(ticker)
        Ticker.onStart = {}
        runtime.enhance(ticker)
        Ticker.onTick = { runtime.reload(ticker) }
        val reloading = assertFailsWith<EnhancementException> { runtime.reload(ticker) }
        assertEquals("Plugin TickerPlugin is already reloading; a reload cannot start another one", reloading.message)

        Ticker.onTick = {}
        runtime.reload(ticker)
    }
}
