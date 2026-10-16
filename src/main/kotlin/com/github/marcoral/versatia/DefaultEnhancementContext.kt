package com.github.marcoral.versatia

import org.bukkit.plugin.Plugin

class DefaultEnhancementContext(
    override val plugin: Plugin,
    override val event: LifecycleEvent,
    override val state: StateStore,
    override val runtimeState: StateStore,
) : EnhancementContext {
    private val completionActions = mutableListOf<() -> Unit>()

    /** Set when the plugin is disabled while it starts or reloads; the processors and steps not yet run are skipped. */
    internal var cancelled = false
        private set

    override fun onPluginEnhanced(action: () -> Unit) {
        completionActions += action
    }

    internal fun cancel() {
        cancelled = true
    }

    internal fun runCompletionActions() {
        val actions = completionActions.toList()
        completionActions.clear()
        runSteps(actions)
    }
}

/**
 * Runs [steps] in order, skipping those left once the plugin has been disabled from one of them. At
 * [LifecycleEvent.STOP] cleanup is best-effort: a failing step does not keep the others from
 * running, and the first failure is thrown at the end with the rest suppressed.
 */
internal fun EnhancementContext.runSteps(steps: List<() -> Unit>) {
    if (event != LifecycleEvent.STOP) return steps.forEach { if (!isCancelled) it() }
    var failure: Exception? = null
    for (step in steps) try {
        step()
    } catch (e: Exception) {
        failure?.addSuppressed(e) ?: run { failure = e }
    }
    failure?.let { throw it }
}

/** Whether the plugin was disabled while this start or reload ran; see [DefaultEnhancementContext.cancelled]. */
internal val EnhancementContext.isCancelled get() = (this as? DefaultEnhancementContext)?.cancelled == true
