package com.github.marcoral.versatia

import org.bukkit.plugin.Plugin

class DefaultEnhancementContext(
    override val plugin: Plugin,
    override val event: LifecycleEvent,
    override val state: StateStore,
    override val runtimeState: StateStore,
) : EnhancementContext {
    private val completionActions = mutableListOf<() -> Unit>()

    override fun onPluginEnhanced(action: () -> Unit) {
        completionActions += action
    }

    internal fun runCompletionActions() {
        val actions = completionActions.toList()
        completionActions.clear()
        actions.forEach { it() }
    }
}
