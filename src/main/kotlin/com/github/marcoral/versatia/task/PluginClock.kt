package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.util.plugin.logSevere
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine

/** The ServerClock of one plugin's coroutines, counting the TickLoop's heartbeats. */
internal class PluginClock(private val lane: Lane, private val loop: TickLoop) : ServerClock {
    override val currentTick get() = loop.tick
    override val timeSource = TimeSource.Monotonic

    override suspend fun delayTicks(ticks: Long) = suspendCancellableCoroutine { loop.schedule(lane, ticks, it) }

    override fun condition(triggers: List<RecheckTrigger>, check: () -> Boolean, owner: Job): Condition =
        PluginCondition.create(lane, loop, triggers, owner, check)

    override suspend fun reportFailedRun(failure: Throwable) =
        lane.plugin.logSevere("${currentCoroutineContext().label()}: a repeated run failed; it runs again on schedule, and the same failure is not logged again until a run succeeds", failure)
}
