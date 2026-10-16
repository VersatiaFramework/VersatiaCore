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
 * which is how a reload addresses them; a plugin counts as running once registered, which happens
 * only when its start completed. Nothing in here knows any particular feature; see docs/runtime.md.
 */
class VersatiaRuntime(
    private val states: PluginStates = PluginStates(),
    private val enhancer: PluginEnhancer = PluginEnhancer(ProcessorLoader()),
) : VersatiaEngine, AutoCloseable {
    override val registry = EnhancedPlugins()
    private val starting = HashMap<Plugin, DefaultEnhancementContext>()
    private val reloading = HashMap<Plugin, DefaultEnhancementContext>()

    override fun enhance(plugin: Plugin) {
        if (plugin in starting || registry.friendlyNameOf(plugin) != null) throw EnhancementException("Plugin ${plugin.name} is already running")
        val store = states.of(plugin.name)
        val context = DefaultEnhancementContext(plugin, LifecycleEvent.START, store, states.runtime)
        val descriptor = DescriptorLoader.load(plugin.pluginMeta.mainClass, context)
        val name = friendlyNameOf(descriptor, plugin)
        registry[name]?.takeIf { it !== plugin }?.let {
            throw EnhancementException("Plugin ${plugin.name}: the friendly name \"$name\" is already used by ${it.name}")
        }
        tracking(plugin, context, store, starting) { enhancer.enhance(descriptor, context) }
        if (!context.cancelled) registry.register(name, plugin)
    }

    override fun reload(plugin: Plugin) {
        when {
            plugin in starting -> throw EnhancementException("Plugin ${plugin.name} is still starting; it can be reloaded once its start has completed")
            registry.friendlyNameOf(plugin) == null -> throw EnhancementException("Plugin ${plugin.name} is not running")
            plugin in reloading -> throw EnhancementException("Plugin ${plugin.name} is already reloading; a reload cannot start another one")
        }
        val store = states.of(plugin.name)
        val context = DefaultEnhancementContext(plugin, LifecycleEvent.RELOAD, store, states.runtime)
        tracking(plugin, context, store, reloading) { enhancer.enhance(DescriptorLoader.load(plugin.pluginMeta.mainClass, context), context) }
    }

    /**
     * Runs a start or reload of [plugin] while it is listed in [inProgress]. A plugin disabled
     * meanwhile has dropped its state, so whatever was stored after that goes too; a failure to
     * close it is suppressed by a failure of [block] instead of hiding it.
     */
    private inline fun tracking(plugin: Plugin, context: DefaultEnhancementContext, store: ClosableStateStore, inProgress: MutableMap<Plugin, DefaultEnhancementContext>, block: () -> Unit) {
        inProgress[plugin] = context
        try {
            block()
        } catch (e: Throwable) {
            inProgress.remove(plugin)
            if (context.cancelled) runCatching { store.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        inProgress.remove(plugin)
        if (context.cancelled) store.close()
    }

    /**
     * A plugin whose start did not complete skips the stop phase; only the state it built is dropped.
     * A start or reload under way is cancelled: nothing more of it runs once the plugin is disabled.
     */
    override fun disable(plugin: Plugin) {
        starting.remove(plugin)?.cancel()
        reloading.remove(plugin)?.cancel()
        if (registry.friendlyNameOf(plugin) == null) return states.drop(plugin.name)
        try {
            run(plugin, LifecycleEvent.STOP)
        } finally {
            try {
                states.drop(plugin.name)
            } finally {
                registry.unregister(plugin)
            }
        }
    }

    override fun close() = states.close()

    private fun run(plugin: Plugin, event: LifecycleEvent) {
        val context = DefaultEnhancementContext(plugin, event, states.of(plugin.name), states.runtime)
        enhancer.enhance(DescriptorLoader.load(plugin.pluginMeta.mainClass, context), context)
    }

    /** `@FriendlyName` recorded on the main class, or the name from `plugin.yml`. */
    private fun friendlyNameOf(descriptor: VersatiaPluginDescriptor, plugin: Plugin) = descriptor.elements
        .firstNotNullOfOrNull { (it.target as? PluginTarget)?.annotations?.occurrenceOf(FriendlyName::class.java.name)?.arguments?.get("value") as? String }
        ?: plugin.name
}
