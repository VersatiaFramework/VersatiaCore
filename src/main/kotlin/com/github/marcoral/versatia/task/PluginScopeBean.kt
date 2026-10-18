package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.StateKey
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.di.ExposePrototype
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.util.plugin.logSevere
import com.github.marcoral.versatia.util.plugin.logWarning
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.bukkit.plugin.Plugin

/**
 * The PluginScope VersatiaCore makes for every plugin injecting it: a supervisor job, the plugin's
 * lane of the TickLoop as dispatcher and its clock. Stopped before the plugin's stop functions by
 * the PluginScopeEnhancementProcessor, otherwise when the plugin's beans are closed. See docs/runtime.md.
 */
@ExposePrototype
internal class PluginScopeBean(private val plugin: Plugin, ticker: ServerTicker) : PluginScope, AutoCloseable {
    private val loop = ticker.loop
    private val job = SupervisorJob()
    private val lane = loop.lane(plugin, job)
    @Volatile private var stopping = false

    override val serverThread = PluginDispatcher(lane, loop)

    private val clock = PluginClock(lane, loop)

    override val coroutineContext = job + serverThread + clock + CoroutineMarker + CoroutineExceptionHandler(::report)

    val closeTimeout get() = loop.closeTimeout

    override fun condition(vararg triggers: RecheckTrigger, check: () -> Boolean) = clock.condition(triggers.toList(), check, job)

    init {
        job.invokeOnCompletion { if (!lane.stopping) plugin.logWarning("PluginScope was cancelled by plugin code, so nothing launched from it runs until the plugin restarts; cancel the jobs you launch, not the scope") }
    }

    /** Cancels every coroutine of the plugin and, on the server thread, waits up to the loop's timeout for them to finish. Idempotent. */
    fun stop() {
        if (stopping) return
        stopping = true
        lane.stopping = true
        job.cancel(CancellationException("Plugin ${plugin.name} is being disabled"))
        if (loop.isServerThread() && !loop.stopped) {
            loop.abandon(lane)
            when {
                CoroutineMarker.onThisThread -> if (!job.isCompleted) plugin.logWarning("Plugin ${plugin.name} is being disabled from inside a running coroutine, so its own coroutines finish only after that coroutine suspends, once the plugin is gone; disable plugins from a command or a listener, not from a coroutine")
                !loop.drain(lane) { job.isCompleted } -> plugin.logWarning(unfinished())
            }
        }
        lane.closed = true
    }

    override fun close() = stop()

    private fun unfinished(): String {
        val running = job.children.toList()
        val names = running.mapNotNull { (it as? CoroutineScope)?.coroutineContext?.get(CoroutineName)?.name }
        return "${running.size} coroutines were still running $closeTimeout after the plugin was disabled${if (names.isEmpty()) "" else " (${names.joinToString()})"}; they are cancelled and finish when they next resume"
    }

    private fun report(context: CoroutineContext, failure: Throwable) =
        plugin.logSevere("${context.label()} failed${if (lane.closed) " after the plugin was disabled" else ""}", failure)

    companion object {
        val KEY = StateKey<PluginScopeBean>("pluginScope")

        /** The plugin's scope, resolved through its beans the first time and kept in its state; null when the plugin has no bean container. */
        fun of(context: EnhancementContext) = context.state[KEY] ?: context.state[BeanResolver.KEY]?.let { beans ->
            val scope = beans.resolve("@RunBackgroundTasks", listOf(Parameter(PluginScope::class.java.name))).single()
            context.state.getOrPut(KEY) {
                scope as? PluginScopeBean ?: throw EnhancementException("Plugin ${context.plugin.name}: the PluginScope bean is ${scope.javaClass.name}, not VersatiaCore's; background tasks and the stop order need VersatiaCore's")
            }
        }
    }
}

/** How log lines name a coroutine: its CoroutineName, or "A coroutine". */
internal fun CoroutineContext.label() = this[CoroutineName]?.let { "Coroutine ${it.name}" } ?: "A coroutine"
