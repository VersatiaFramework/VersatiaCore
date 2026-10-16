package com.github.marcoral.versatia

import com.github.marcoral.versatia.di.ExposedBeanRegistry
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
 * only when its start completed. Nothing in here knows any particular feature, except that a reload
 * follows the exposed-bean registry to the plugins holding beans of the reloaded one that follow
 * its reloads (those with `@Value` properties). Starting, reloading and disabling touch Bukkit
 * state, so each refuses to run off the server's main thread; with a [debugLog] every step is
 * described in the log while debug mode is on. See docs/runtime.md.
 */
class VersatiaRuntime(
    private val states: PluginStates = PluginStates(),
    private val enhancer: PluginEnhancer = PluginEnhancer(ProcessorLoader()),
    debugLog: DebugLog? = null,
) : VersatiaEngine, AutoCloseable {
    override val registry = EnhancedPlugins()
    private val starting = HashMap<Plugin, DefaultEnhancementContext>()
    private val reloading = HashMap<Plugin, DefaultEnhancementContext>()
    private var cascade: Cascade? = null

    init {
        debugLog?.let { states.runtime.getOrPut(DebugLog.KEY) { it } }
    }

    override fun enhance(plugin: Plugin) {
        checkMainThread(plugin, "start")
        if (plugin in starting || registry.friendlyNameOf(plugin) != null) throw EnhancementException("Plugin ${plugin.name} is already running")
        val store = states.of(plugin.name)
        val context = DefaultEnhancementContext(plugin, LifecycleEvent.START, store, states.runtime)
        val descriptor = DescriptorLoader.load(plugin.pluginMeta.mainClass, context)
        val name = friendlyNameOf(descriptor, plugin)
        registry.checkAvailable(name, plugin)
        tracking(plugin, context, store, starting) { enhancer.enhance(descriptor, context) }
        if (!context.cancelled) registry.register(name, plugin)
    }

    /** Reloads [plugin] and the plugins using its beans; a failure of [plugin] is thrown, one of the others logged. */
    override fun reload(plugin: Plugin) {
        checkMainThread(plugin, "reload")
        val outcomes = reload(listOf(plugin))
        outcomes.first { it.plugin === plugin }.failure?.let { throw it }
        val cascaded = outcomes.filter { it.cause != null }
        cascaded.forEach { it.log(plugin.logger) }
        ReloadOutcome.summary(cascaded)?.let(plugin.logger::info)
    }

    /**
     * Reloads [requested] and then every running plugin holding a bean exposed by a plugin that
     * reloaded, of a class marked to follow its owner's reloads (one with `@Value` properties),
     * transitively, each once and in start order; a plugin whose reload failed passes
     * nothing on. Failures are part of the outcomes, never thrown, except a call off the main
     * thread, which reloads nothing. Called while a reload runs, as from a reload function, it
     * joins that reload: a plugin it already went through is refused.
     */
    fun reload(requested: Collection<Plugin>): List<ReloadOutcome> {
        requested.forEach { checkMainThread(it, "reload") }
        cascade?.let { running -> return requested.map { running.run(it, null) } }
        val running = Cascade().also { cascade = it }
        try {
            requested.forEach { running.pending.putIfAbsent(it, null) }
            while (running.pending.isNotEmpty()) {
                val next = registry.plugins.values.firstOrNull { it in running.pending } ?: running.pending.keys.first()
                running.run(next, running.pending.getValue(next))
            }
            return running.outcomes.values.toList()
        } finally {
            cascade = null
        }
    }

    private inner class Cascade {
        val outcomes = LinkedHashMap<Plugin, ReloadOutcome>()
        val pending = LinkedHashMap<Plugin, Plugin?>()

        fun run(plugin: Plugin, cause: Plugin?): ReloadOutcome {
            val name = nameOf(plugin)
            if (plugin in outcomes) return ReloadOutcome(name, plugin, null, EnhancementException("Plugin ${plugin.name} has already been reloaded by the reload under way; a plugin reloads at most once per reload"))
            pending.remove(plugin)
            val failure = try {
                reloadAlone(plugin)
                null
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                e
            }
            val outcome = ReloadOutcome(name, plugin, cause?.let(::nameOf), failure)
            outcomes[plugin] = outcome
            if (failure == null) consumersOf(plugin).let { consumers ->
                debug(plugin) { if (consumers.isEmpty()) "no running plugin holds a bean that follows its reloads" else "goes on to ${consumers.joinToString { nameOf(it) }}, holding beans that follow its reloads" }
                consumers.forEach { if (it !in outcomes) pending.putIfAbsent(it, plugin) }
            }
            return outcome
        }
    }

    private fun nameOf(plugin: Plugin) = registry.friendlyNameOf(plugin) ?: plugin.name

    /** The running plugins holding a bean [plugin] exposes whose state follows its reloads, in start order. */
    private fun consumersOf(plugin: Plugin): List<Plugin> {
        val names = states.runtime[ExposedBeanRegistry.KEY]?.consumersReloadedWith(plugin.name).orEmpty()
        return registry.plugins.values.filter { it.name in names && it !== plugin }
    }

    private fun reloadAlone(plugin: Plugin) {
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
        checkMainThread(plugin, "stop")
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

    /** The state kept for [plugin] while it runs, read by diagnostics such as `/versatia info`; null when it is not running. */
    fun stateOf(plugin: Plugin): StateStore? = if (registry.friendlyNameOf(plugin) != null) states.of(plugin.name) else null

    /** The state shared by all plugins, such as the exposed-bean registry. */
    val runtimeState: StateStore get() = states.runtime

    private fun debug(plugin: Plugin, message: () -> String) = states.runtime[DebugLog.KEY]?.log(plugin, LifecycleEvent.RELOAD, message)

    private fun checkMainThread(plugin: Plugin, action: String) {
        if (!plugin.server.isPrimaryThread)
            throw EnhancementException("Plugin ${plugin.name}: $action must run on the server's main thread; schedule it with plugin.runTask { ... }")
    }

    private fun run(plugin: Plugin, event: LifecycleEvent) {
        val context = DefaultEnhancementContext(plugin, event, states.of(plugin.name), states.runtime)
        enhancer.enhance(DescriptorLoader.load(plugin.pluginMeta.mainClass, context), context)
    }

    /** `@FriendlyName` recorded on the main class, or the name from `plugin.yml`. */
    private fun friendlyNameOf(descriptor: VersatiaPluginDescriptor, plugin: Plugin) = descriptor.elements
        .firstNotNullOfOrNull { (it.target as? PluginTarget)?.annotations?.occurrenceOf(FriendlyName::class.java.name)?.arguments?.get("value") as? String }
        ?: plugin.name
}
