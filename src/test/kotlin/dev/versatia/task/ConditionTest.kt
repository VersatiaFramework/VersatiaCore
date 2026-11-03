package dev.versatia.task

import dev.versatia.testsupport.captureLog
import dev.versatia.testsupport.fakePlugin
import dev.versatia.testsupport.handlersOf
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class Ping(val id: Int = 0, async: Boolean = false, private var cancelled: Boolean = false) : Event(async), Cancellable {
    override fun isCancelled() = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers() = handlerList

    companion object {
        private val handlerList = HandlerList()

        @JvmStatic
        fun getHandlerList() = handlerList
    }
}

/** Conditions of a PluginScope: when their check runs, what triggers it and when their listeners go; see docs/runtime.md. */
class ConditionTest {
    private val ticker = ServerTicker(fakePlugin("ConditionCore"))
    private val loop = ticker.loop
    private val alpha = fakePlugin("ConditionAlpha")
    private val scope = PluginScopeBean(alpha, ticker)
    private val log = alpha.captureLog()

    @AfterTest
    fun tearDown() {
        HandlerList.unregisterAll(alpha)
        log.close()
        ticker.close()
    }

    private fun fire(event: Event) = alpha.server.pluginManager.callEvent(event)

    private fun listeners() = handlersOf(Ping::class.java).registeredListeners.count { it.plugin === alpha }

    @Test
    fun `the check runs when the condition is created`() {
        var checks = 0
        val condition = scope.condition {
            checks++
            true
        }
        assertEquals(1, checks)
        assertTrue(condition.value)
    }

    @Test
    fun `a recheck on the server thread updates the value before it returns`() {
        var open = false
        val condition = scope.condition { open }
        open = true

        condition.recheck()
        assertTrue(condition.value)
    }

    @Test
    fun `a recheck from another thread updates the value once at the next heartbeat`() {
        val open = AtomicBoolean()
        val checks = AtomicInteger()
        val condition = scope.condition {
            checks.incrementAndGet()
            open.get()
        }
        open.set(true)

        thread {
            condition.recheck()
            condition.recheck()
        }.join()
        assertFalse(condition.value)
        loop.heartbeat()
        assertTrue(condition.value)
        assertEquals(2, checks.get(), "the two rechecks are checked once")
    }

    @Test
    fun `events of a trigger recheck once at the next heartbeat however many fired`() {
        var checks = 0
        scope.condition(recheckOn<Ping>()) {
            checks++
            true
        }

        repeat(5) { fire(Ping()) }
        assertEquals(1, checks)
        loop.heartbeat()
        assertEquals(2, checks)
        loop.heartbeat()
        assertEquals(2, checks)
    }

    @Test
    fun `the filter and ignoreCancelled of a trigger pick the events that recheck`() {
        var checks = 0
        scope.condition(recheckOn<Ping> { it.id == 1 }) {
            checks++
            true
        }
        var cancelledChecks = 0
        scope.condition(recheckOn<Ping>(ignoreCancelled = false)) {
            cancelledChecks++
            true
        }

        fire(Ping(2))
        loop.heartbeat()
        assertEquals(1, checks)
        fire(Ping(1, cancelled = true))
        loop.heartbeat()
        assertEquals(1, checks, "a cancelled event is ignored by default")
        assertEquals(3, cancelledChecks)
        fire(Ping(1))
        loop.heartbeat()
        assertEquals(2, checks)
    }

    @Test
    fun `an immediate trigger fired on the server thread rechecks inside the handler`() {
        var open = false
        val condition = scope.condition(recheckOn<Ping>(immediately = true)) { open }
        open = true

        fire(Ping())
        assertTrue(condition.value)
    }

    @Test
    fun `an immediate trigger fired on another thread warns once and rechecks at the next heartbeat`() {
        val open = AtomicBoolean()
        val condition = scope.condition(recheckOn<Ping>(immediately = true)) { open.get() }
        val other = scope.condition(recheckOn<Ping>(immediately = true)) { open.get() }
        open.set(true)

        thread {
            fire(Ping(async = true))
            fire(Ping(async = true))
        }.join()
        assertFalse(condition.value)
        loop.heartbeat()
        assertTrue(condition.value)
        assertTrue(other.value)
        thread { fire(Ping(async = true)) }.join()

        val warning = log.messages(Level.WARNING).single()
        assertContains(warning, "recheckOn<Ping>(immediately = true) got Ping on the thread ")
        assertContains(warning, "so it is checked at the next tick instead. Remove immediately = true for this event")
    }

    @Test
    fun `a check failing for a trigger keeps the last value and is logged`() {
        var broken = false
        val condition = scope.condition(recheckOn<Ping>()) {
            check(!broken) { "broken check" }
            true
        }
        broken = true

        fire(Ping())
        loop.heartbeat()
        assertTrue(condition.value)
        val failure = log.records.single { it.level == Level.SEVERE }
        assertEquals("A condition check failed; the condition keeps its last value, true", failure.message)
        assertEquals("broken check", failure.thrown.message)
    }

    @Test
    fun `the top-level condition stops listening when the coroutine that made it completes`() {
        var value = false
        val job = scope.launch {
            value = condition(recheckOn<Ping>()) { true }.value
            delayTick()
        }
        assertTrue(value)
        assertEquals(1, listeners())

        loop.heartbeat()
        assertTrue(job.isCompleted)
        assertEquals(0, listeners())
    }

    @Test
    fun `a closed condition stops listening, keeps its value and ignores rechecks, however often it is closed`() {
        var open = true
        var checks = 0
        val condition = scope.condition(recheckOn<Ping>()) {
            checks++
            open
        }
        fire(Ping())
        open = false
        condition.close()
        condition.close()
        assertEquals(0, listeners())

        loop.heartbeat()
        condition.recheck()
        thread { condition.recheck() }.join()
        loop.heartbeat()
        assertTrue(condition.value)
        assertEquals(1, checks)
    }

    @Test
    fun `use closes a coroutine's condition before the coroutine completes`() {
        var listening = -1
        val job = scope.launch {
            condition(recheckOn<Ping>()) { true }.use { delayTick() }
            listening = listeners()
            delayTick()
        }
        assertEquals(1, listeners())

        loop.heartbeat()
        assertEquals(0, listening)
        loop.heartbeat()
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a condition of the scope stops listening when the scope stops`() {
        scope.condition(recheckOn<Ping>()) { true }
        assertEquals(1, listeners())

        scope.stop()
        assertEquals(0, listeners())
    }

    @Test
    fun `runOnceWhile cancels a countdown when the condition turns false in the tick the countdown ends`() {
        var standing = true
        val still = scope.condition(recheckOn<Ping>()) { standing }
        val outcome = mutableListOf<String>()
        scope.launch {
            val completed = runOnceWhile(still) {
                delayTicks(3)
                outcome += "teleported"
            }
            outcome += if (completed) "completed" else "interrupted"
        }
        repeat(2) { loop.heartbeat() }

        standing = false
        fire(Ping())
        loop.heartbeat()
        assertEquals(listOf("interrupted"), outcome, "the recheck runs before the timer due in the same heartbeat")
    }

    @Test
    fun `runOnceWhile completes a countdown while the condition holds`() {
        val still = scope.condition(recheckOn<Ping>()) { true }
        val outcome = mutableListOf<String>()
        scope.launch {
            val completed = runOnceWhile(still) {
                delayTicks(3)
                outcome += "teleported"
            }
            outcome += if (completed) "completed" else "interrupted"
        }
        repeat(2) { loop.heartbeat() }

        fire(Ping())
        loop.heartbeat()
        assertEquals(listOf("teleported", "completed"), outcome)
    }
}
