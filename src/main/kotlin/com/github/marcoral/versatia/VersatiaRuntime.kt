package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.DescriptorLoader
import org.bukkit.plugin.Plugin

/**
 * The engine: reads a plugin's descriptor at each lifecycle event, hands it to the processors it
 * names, and keeps the state those processors build, per plugin until it is disabled and for the
 * runtime until it is closed. Nothing in here knows any particular feature; see docs/runtime.md.
 */
class VersatiaRuntime(
    private val states: PluginStates = PluginStates(),
    private val enhancer: PluginEnhancer = PluginEnhancer(ProcessorLoader()),
) : VersatiaEngine, AutoCloseable {
    override fun enhance(plugin: Plugin) = run(plugin, LifecycleEvent.START)

    override fun disable(plugin: Plugin) {
        try {
            run(plugin, LifecycleEvent.STOP)
        } finally {
            states.drop(plugin.name)
        }
    }

    override fun close() = states.close()

    private fun run(plugin: Plugin, event: LifecycleEvent) {
        val context = DefaultEnhancementContext(plugin, event, states.of(plugin.name), states.runtime)
        enhancer.enhance(DescriptorLoader.load(plugin.description.main, context), context)
    }
}
