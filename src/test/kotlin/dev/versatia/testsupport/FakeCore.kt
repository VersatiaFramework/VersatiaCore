package dev.versatia.testsupport

import dev.versatia.PluginEnhancer
import dev.versatia.PluginStates
import dev.versatia.ProcessorLoader
import dev.versatia.di.BeanResolver
import dev.versatia.engine.descriptor.DescriptorLoader
import dev.versatia.task.ServerTicker
import dev.versatia.task.TickLoop
import dev.versatia.testsupport.core.VersatiaDescriptor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * VersatiaCore as far as plugins see it, enhanced into [states] from a hand-written descriptor:
 * it exposes the PluginScope prototype every plugin extending VersatiaPlugin injects, so enhance
 * it before them. Its ServerTicker's timer is recorded by the [FakeScheduler] and never fires by
 * itself; tests drive the TickLoop with [heartbeat] on the thread creating the core, which is the
 * server thread.
 */
internal class FakeCore(private val states: PluginStates) {
    val plugin = fakePlugin(NAME, main = VersatiaDescriptor.MAIN)

    init {
        FakeScheduler.install()
        val context = testContext(NAME, states, plugin = plugin)
        PluginEnhancer(ProcessorLoader()).enhance(DescriptorLoader.load(VersatiaDescriptor.MAIN, context), context)
    }

    val ticker = states.of(NAME)[BeanResolver.KEY]!!.instanceOf(ServerTicker::class.java.name) as ServerTicker

    val loop get() = ticker.loop

    /** Runs [times] server ticks, as the ServerTicker's timer would. */
    fun heartbeat(times: Int = 1) = repeat(times) { loop.heartbeat() }

    /** Disables VersatiaCore the way the server would: its state goes, which closes the ServerTicker. */
    fun stop() = states.drop(NAME)

    companion object {
        const val NAME = "VersatiaCore"
    }
}

/**
 * Runs heartbeats until [condition] holds, for a real round trip through another thread such as
 * `Dispatchers.IO`; fails after [timeout].
 */
internal fun TickLoop.heartbeatUntil(timeout: Duration = 10.seconds, condition: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!condition()) {
        check(!deadline.hasPassedNow()) { "The condition did not hold within $timeout of heartbeats" }
        Thread.sleep(1)
        heartbeat()
    }
}
