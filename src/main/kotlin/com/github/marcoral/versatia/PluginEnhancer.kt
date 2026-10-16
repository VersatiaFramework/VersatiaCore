package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.ElementTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor

/**
 * Dispatches a plugin's descriptor: the elements whose phase applies to the event and whose
 * `@ConditionalOnPlugin` plugins were enabled when the plugin started, grouped by the processor
 * they name, each group to its processor. The groups run in the order of their first element in
 * the descriptor. Every processor is loaded before any of them runs, so a missing one leaves the
 * plugin untouched; at stop every processor runs even when another fails. See docs/runtime.md.
 */
class PluginEnhancer(private val processors: ProcessorLoader) {
    fun enhance(descriptor: VersatiaPluginDescriptor, context: DefaultEnhancementContext) {
        val absent = absentPlugins(descriptor, context)
        val missing = descriptor.elements.filter { it.phase.appliesTo(context.event) }.map { it to requiredPlugins(it.target).filter(absent::contains) }
        val skipped = missing.filter { (_, plugins) -> plugins.isNotEmpty() }
        if (skipped.isNotEmpty() && context.event == LifecycleEvent.START) logSkipped(skipped, context)
        val groups = missing.filter { (_, plugins) -> plugins.isEmpty() }.map { (element, _) -> element }.groupBy { it.processorClass }
        if (context.event == LifecycleEvent.STOP) {
            val steps = groups.map { (processorClass, elements) -> { processors.load(processorClass, context).process(elements, context) } }
            return context.runSteps(steps + { context.runCompletionActions() })
        }
        val loaded = groups.keys.associateWith { processors.load(it, context) }
        for ((processorClass, elements) in groups) if (!context.cancelled) loaded.getValue(processorClass).process(elements, context)
        context.runCompletionActions()
    }

    /** One line per missing plugin, telling a plugin that is not installed from one that had not started yet. */
    private fun logSkipped(skipped: List<Pair<EnhancedElement, List<String>>>, context: EnhancementContext) {
        val classesByPlugin = skipped.flatMap { (element, plugins) -> plugins.map { it to element.target.className.substringAfterLast('.') } }
            .groupBy({ it.first }, { it.second })
        for ((plugin, classes) in classesByPlugin) {
            val reason = if (context.plugin.server.pluginManager.getPlugin(plugin) == null) "$plugin is not installed"
            else "$plugin is installed but was not enabled before ${context.plugin.name}; list it under softdepend: in plugin.yml"
            context.plugin.logger.info("Skipped ${classes.distinct().joinToString()}: $reason")
        }
    }

    /**
     * The `@ConditionalOnPlugin` plugins that are not enabled, decided once when the plugin starts:
     * an element skipped then stays skipped at every reload and at stop, like the beans it would have made.
     */
    private fun absentPlugins(descriptor: VersatiaPluginDescriptor, context: EnhancementContext): Set<String> {
        if (context.event != LifecycleEvent.START) context.state[ABSENT_PLUGINS]?.let { return it }
        val absent = descriptor.elements.flatMap { requiredPlugins(it.target) }.distinct()
            .filterNot { context.plugin.server.pluginManager.isPluginEnabled(it) }.toSet()
        return if (context.event == LifecycleEvent.START) context.state.getOrPut(ABSENT_PLUGINS) { absent } else absent
    }

    private fun requiredPlugins(target: ElementTarget): List<String> {
        val annotations = when (target) {
            is ClassTarget -> target.annotations
            is FunctionTarget -> target.annotations + target.classAnnotations
            is PropertyTarget -> target.annotations + target.classAnnotations
            is PluginTarget -> target.annotations
        }
        return annotations.filter { it.annotationClass == ConditionalOnPlugin::class.java.name }
            .flatMap { (it.arguments["value"] as? List<*>).orEmpty() }.map { it.toString() }.distinct()
    }

    private companion object {
        val ABSENT_PLUGINS = StateKey<Set<String>>("ConditionalOnPlugin.absentPlugins")
    }
}
