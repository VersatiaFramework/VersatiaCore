package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.util.plugin.logSevere
import com.github.marcoral.versatia.util.plugin.logWarning
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

/**
 * A Condition of one plugin: its check runs on the server thread, at once for a recheck called
 * there and for an `immediately` trigger fired there, otherwise once at the lane's next turn
 * however many triggers fired. Its listeners are Bukkit's until [close], called by the author or
 * when its owner completes, whose completion handler goes with it. See docs/runtime.md.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class PluginCondition private constructor(
    private val lane: Lane,
    private val loop: TickLoop,
    private val check: () -> Boolean,
    private val state: MutableStateFlow<Boolean>,
) : Condition, StateFlow<Boolean> by state, Listener {
    private val pending = AtomicBoolean()
    private val closed = AtomicBoolean()

    @Volatile
    private var ownerLink: DisposableHandle? = null

    override fun recheck() {
        if (closed.get()) return
        if (loop.isServerThread()) state.value = check() else recheckAtNextTick()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        HandlerList.unregisterAll(this)
        ownerLink?.dispose()
    }

    /** Closes the condition when [owner] completes; the link goes when the condition is closed first. */
    private fun bindTo(owner: Job) {
        ownerLink = owner.invokeOnCompletion { close() }
        if (closed.get()) ownerLink?.dispose()
    }

    private fun recheckAtNextTick() {
        if (pending.compareAndSet(false, true)) loop.post(lane) {
            pending.set(false)
            evaluate()
        }
    }

    /** Evaluates for a trigger: a failing check keeps the last value and is logged, not thrown into the event or the loop. */
    private fun evaluate() {
        if (closed.get()) return
        try {
            state.value = check()
        } catch (e: Exception) {
            lane.plugin.logSevere("A condition check failed; the condition keeps its last value, ${state.value}", e)
        }
    }

    private fun listen(trigger: RecheckTrigger) = lane.plugin.server.pluginManager.registerEvent(
        trigger.event,
        this,
        trigger.priority,
        EventExecutor { _, event -> if (trigger.event.isInstance(event) && trigger.filter(event)) fire(trigger, event) },
        lane.plugin,
        trigger.ignoreCancelled,
    )

    private fun fire(trigger: RecheckTrigger, event: Event) = when {
        !trigger.immediately -> recheckAtNextTick()
        loop.isServerThread() -> evaluate()
        else -> {
            if (lane.warnedImmediately.add(trigger.event)) lane.plugin.logWarning(
                "recheckOn<${trigger.event.simpleName}>(immediately = true) got ${event.eventName} on the thread ${Thread.currentThread().name}, not the server thread; " +
                    "conditions are always checked on the server thread, so it is checked at the next tick instead. Remove immediately = true for this event",
            )
            recheckAtNextTick()
        }
    }

    companion object {
        /** A condition evaluated now, listening to [triggers] and closed with [owner]; created on the server thread. */
        fun create(lane: Lane, loop: TickLoop, triggers: List<RecheckTrigger>, owner: Job, check: () -> Boolean): PluginCondition {
            kotlin.check(loop.isServerThread()) { "A condition is created on the server thread, where its check runs; create it in a bean, a start function or a coroutine of the plugin" }
            return PluginCondition(lane, loop, check, MutableStateFlow(check())).also { condition ->
                triggers.forEach(condition::listen)
                condition.bindTo(owner)
            }
        }
    }
}
