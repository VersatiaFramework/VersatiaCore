package dev.versatia.task

import dev.versatia.EnhancementContext
import dev.versatia.EnhancementException
import dev.versatia.EnhancementProcessor
import dev.versatia.LifecycleEvent
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.PluginTarget

/**
 * Binds the plugin's PluginScope to its lifecycle: at start it is resolved through the plugin's
 * beans and kept in its state; at stop every coroutine of the plugin is cancelled and awaited
 * before the stop functions run. See docs/runtime.md.
 */
class PluginScopeEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        if (elements.singleOrNull()?.target !is PluginTarget) throw EnhancementException("${javaClass.name} expects the plugin's main class exactly once, got ${elements.map { it.target }}")
        when (context.event) {
            LifecycleEvent.START -> context.onPluginEnhanced { PluginScopeBean.of(context) }
            LifecycleEvent.STOP -> context.state[PluginScopeBean.KEY]?.stop()
            LifecycleEvent.RELOAD -> Unit
        }
    }
}
