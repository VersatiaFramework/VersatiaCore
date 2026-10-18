package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.subclassInstanceOf
import com.github.marcoral.versatia.failure

/**
 * Runs the plugin's BackgroundTasks in its PluginScope: launched at start, restarted at every
 * reload once the values are set and the reloadable beans reloaded; the scope cancels them at
 * stop. See docs/runtime.md.
 */
class RunBackgroundTasksEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = when (context.event) {
        LifecycleEvent.START -> context.onPluginEnhanced { start(elements, context) }
        LifecycleEvent.RELOAD -> context.onPluginEnhanced { context.state[BackgroundTasks.KEY]?.restart() }
        LifecycleEvent.STOP -> Unit
    }

    private fun start(elements: List<EnhancedElement>, context: EnhancementContext) {
        val scope = PluginScopeBean.of(context)
            ?: throw context.failure("${elements.first().target.className} is a BackgroundTask, but the plugin has no bean container; extend VersatiaPlugin or put @PluginBean on the main class")
        val tasks = elements.map { element ->
            context.subclassInstanceOf(element, this) as? BackgroundTask ?: throw context.failure("${element.target.className} is not a BackgroundTask")
        }
        context.state.getOrPut(BackgroundTasks.KEY) { BackgroundTasks(context.plugin, scope, tasks) }.start()
    }
}
