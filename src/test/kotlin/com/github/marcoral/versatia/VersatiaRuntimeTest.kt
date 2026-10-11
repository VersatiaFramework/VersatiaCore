package com.github.marcoral.versatia

import com.github.marcoral.versatia.testsupport.Recorder
import com.github.marcoral.versatia.testsupport.fakePlugin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The runtime over hand-written descriptors: the registry of enhanced plugins, friendly names and the reload event. */
class VersatiaRuntimeTest {
    private val runtime = VersatiaRuntime()

    private val tickerMain = "com.github.marcoral.versatia.sample.TickerPlugin"
    private val plainMain = "com.github.marcoral.versatia.engine.descriptor.sample.SamplePlugin"

    @BeforeTest
    fun setUp() = Recorder.reset()

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
}
