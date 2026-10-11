package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.DescriptorLoader
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor
import com.github.marcoral.versatia.engine.descriptor.occurrenceOf
import org.bukkit.plugin.Plugin

/**
 * The engine: reads a plugin's descriptor at each lifecycle event, hands it to the processors it
 * names, and keeps the state those processors build, per plugin until it is disabled and for the
 * runtime until it is closed. Its [registry] keeps the enhanced plugins by their friendly names,
 * which is how a reload addresses them. Nothing in here knows any particular feature; see docs/runtime.md.
 */
class VersatiaRuntime(
    private val states: PluginStates = PluginStates(),
    private val enhancer: PluginEnhancer = PluginEnhancer(ProcessorLoader()),
) : VersatiaEngine, AutoCloseable {
    override val registry = EnhancedPlugins()

    override fun enhance(plugin: Plugin) {
        val context = context(plugin, LifecycleEvent.START)
        val descriptor = DescriptorLoader.load(plugin.description.main, context)
        val name = friendlyNameOf(descriptor, plugin)
        registry[name]?.takeIf { it !== plugin }?.let {
            throw EnhancementException("Plugin ${plugin.name}: the friendly name \"$name\" is already used by ${it.name}")
        }
        enhancer.enhance(descriptor, context)
        registry.register(name, plugin)
    }

    override fun reload(plugin: Plugin) = run(plugin, LifecycleEvent.RELOAD)

    override fun disable(plugin: Plugin) {
        try {
            run(plugin, LifecycleEvent.STOP)
        } finally {
            states.drop(plugin.name)
            registry.unregister(plugin)
        }
    }

    override fun close() = states.close()

    private fun run(plugin: Plugin, event: LifecycleEvent) {
        val context = context(plugin, event)
        enhancer.enhance(DescriptorLoader.load(plugin.description.main, context), context)
    }

    private fun context(plugin: Plugin, event: LifecycleEvent) = DefaultEnhancementContext(plugin, event, states.of(plugin.name), states.runtime)

    /** `@FriendlyName` recorded on the main class, or the name from `plugin.yml`. */
    private fun friendlyNameOf(descriptor: VersatiaPluginDescriptor, plugin: Plugin) = descriptor.elements
        .firstNotNullOfOrNull { (it.target as? PluginTarget)?.annotations?.occurrenceOf(FriendlyName::class.java.name)?.arguments?.get("value") as? String }
        ?: plugin.name
}
