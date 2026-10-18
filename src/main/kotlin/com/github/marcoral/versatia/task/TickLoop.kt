package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.util.plugin.logSevere
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.bukkit.plugin.Plugin

/** One plugin's queues on the server thread; [job] is the root of the plugin's scope. */
internal class Lane(val plugin: Plugin, val job: Job) {
    val queue = ConcurrentLinkedQueue<Dispatched>()
    val rechecks = ConcurrentLinkedQueue<() -> Unit>()

    /** Event types whose `immediately` triggers already warned that they fired on another thread. */
    val warnedImmediately: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    /** The plugin is being disabled: tick waits and rechecks are refused, pending ones cancelled. */
    @Volatile var stopping = false

    /** The plugin is disabled; blocks that still arrive run at the next heartbeat and their failures say so. */
    @Volatile var closed = false
}

internal class Dispatched(val context: CoroutineContext, val block: Runnable)

private class Timer(val lane: Lane, val due: Long, val sequence: Long, val continuation: CancellableContinuation<Unit>) {
    @Volatile var cancelled = false
}

/**
 * The server-thread loop behind every plugin's PluginScope: driven once per tick by [heartbeat],
 * it runs the blocks dispatched to each plugin's lane and the condition rechecks posted to it, and
 * then resumes the due tick timers. See docs/runtime.md.
 */
internal class TickLoop(val isServerThread: () -> Boolean, var closeTimeout: Duration = 5.seconds) {
    @Volatile var tick = 0L
        private set

    @Volatile var stopped = false
        private set

    private val lanes = CopyOnWriteArrayList<Lane>()
    private val newTimers = ConcurrentLinkedQueue<Timer>()
    private val timers = PriorityQueue(compareBy<Timer>({ it.due }, { it.sequence }))
    private val sequence = AtomicLong()
    private val cancelledTimers = AtomicInteger()

    /** Registers a lane; lanes run in the order they were registered, which is the plugins' start order. */
    fun lane(plugin: Plugin, job: Job) = Lane(plugin, job).also { lanes += it }

    /** Queues [block] for the lane's next turn; any thread, never throws, never runs [block] in place. */
    fun submit(lane: Lane, context: CoroutineContext, block: Runnable) {
        val entry = Dispatched(context, block)
        if (stopped) return handOff(entry)
        lane.queue += entry
        if (stopped && lane.queue.remove(entry)) handOff(entry)
        // A closed lane is pruned once it is empty; a straggler arriving later brings it back
        if (lane.closed) lanes.addIfAbsent(lane)
    }

    /** Runs [recheck] at the lane's next turn, before the tick timers of that heartbeat; any thread. Dropped once the plugin is being disabled. */
    fun post(lane: Lane, recheck: () -> Unit) {
        if (!stopped && !lane.stopping) lane.rechecks += recheck
    }

    /** Resumes [continuation] once [ticks] more ticks have begun, at least one; any thread. */
    fun schedule(lane: Lane, ticks: Long, continuation: CancellableContinuation<Unit>) {
        refusal(lane)?.let { continuation.cancel(it); return }
        val timer = Timer(lane, tick + maxOf(ticks, 1), sequence.incrementAndGet(), continuation)
        continuation.invokeOnCancellation {
            timer.cancelled = true
            cancelledTimers.incrementAndGet()
        }
        newTimers += timer
    }

    /** Cancels the lane's pending tick timers and drops its rechecks; server thread. */
    fun abandon(lane: Lane) {
        val reason = disabling(lane)
        (timers.toList() + newTimers.toList()).filter { it.lane === lane }.forEach { it.continuation.cancel(reason) }
        lane.rechecks.clear()
    }

    /** Runs the lane's blocks until [until] holds or [closeTimeout] passed; false on timeout. Server thread. */
    fun drain(lane: Lane, until: () -> Boolean): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + closeTimeout
        while (!until()) {
            if (deadline.hasPassedNow()) return false
            val entry = lane.queue.poll()
            if (entry == null) LockSupport.parkNanos(PARK_NANOS) else guarded(lane) { entry.block.run() }
        }
        return true
    }

    /** One server tick: per lane the blocks and rechecks queued before its turn, then the due timers. Server thread. */
    fun heartbeat() {
        tick++
        for (lane in lanes) {
            runQueued(lane)
            runRechecks(lane)
            if (lane.closed && lane.job.isCompleted && lane.queue.isEmpty()) prune(lane)
        }
        fireTimers()
    }

    /**
     * Cancels every lane, runs what comes back until every lane's job completed or [closeTimeout]
     * passed, then hands later blocks to `Dispatchers.IO`; returns the lanes whose job did not
     * complete. Server thread, called when VersatiaCore stops.
     */
    fun stop(): List<Lane> {
        val all = lanes.toList()
        for (lane in all) {
            lane.stopping = true
            lane.job.cancel(CancellationException(CORE_STOPPED))
        }
        all.forEach(::abandon)
        if (!CoroutineMarker.onThisThread) {
            val deadline = TimeSource.Monotonic.markNow() + closeTimeout
            while (all.any { !it.job.isCompleted } && !deadline.hasPassedNow())
                if (all.sumOf { runQueued(it) } == 0) LockSupport.parkNanos(PARK_NANOS)
        }
        stopped = true
        for (lane in all) while (true) handOff(lane.queue.poll() ?: break)
        return all.filter { !it.job.isCompleted }
    }

    /** Runs the entries queued before the lane's turn, so a block re-dispatching itself advances once per tick; returns how many ran. */
    private fun runQueued(lane: Lane): Int {
        var ran = 0
        repeat(lane.queue.size) {
            val entry = lane.queue.poll() ?: return ran
            guarded(lane) { entry.block.run() }
            ran++
        }
        return ran
    }

    private fun runRechecks(lane: Lane) {
        repeat(lane.rechecks.size) {
            val recheck = lane.rechecks.poll() ?: return
            guarded(lane, recheck)
        }
    }

    private fun prune(lane: Lane) {
        lanes.remove(lane)
        if (lane.queue.isNotEmpty()) lanes.addIfAbsent(lane)
    }

    private fun fireTimers() {
        while (true) {
            val timer = newTimers.poll() ?: break
            if (timer.cancelled) cancelledTimers.decrementAndGet() else timers += timer
        }
        while (true) {
            val timer = timers.peek()?.takeIf { it.due <= tick } ?: break
            timers.poll()
            when {
                timer.cancelled -> cancelledTimers.decrementAndGet()
                timer.lane.stopping -> timer.continuation.cancel(disabling(timer.lane))
                else -> guarded(timer.lane) { timer.continuation.resume(Unit) }
            }
        }
        if (cancelledTimers.get() > timers.size / 2 + COMPACTION_SLACK) {
            timers.removeIf { it.cancelled }
            cancelledTimers.set(0)
        }
    }

    /** Why a wait of [lane] is refused: the loop stopped or the lane's plugin is being disabled; null when it is not. */
    private fun refusal(lane: Lane) = when {
        stopped -> CancellationException(CORE_STOPPED)
        lane.stopping -> disabling(lane)
        else -> null
    }

    private fun disabling(lane: Lane) = CancellationException("Plugin ${lane.plugin.name} is being disabled; no tick passes for it any more")

    private inline fun guarded(lane: Lane, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            lane.plugin.logSevere("A coroutine threw while running on the server thread", e)
        }
    }

    private fun handOff(entry: Dispatched) {
        entry.context.cancel(CancellationException(CORE_STOPPED))
        Dispatchers.IO.dispatch(entry.context, entry.block)
    }

    private companion object {
        const val CORE_STOPPED = "VersatiaCore stopped"
        const val PARK_NANOS = 1_000_000L
        const val COMPACTION_SLACK = 64
    }
}
