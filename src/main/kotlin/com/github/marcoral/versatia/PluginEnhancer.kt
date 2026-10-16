package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ElementTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.MemberTarget
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
        debug(descriptor, skipped, groups, context)
        if (context.event == LifecycleEvent.STOP) {
            val steps = groups.map { (processorClass, elements) -> { processors.load(processorClass, context).process(elements, context) } }
            return context.runSteps(steps + { context.runCompletionActions() })
        }
        val loaded = groups.keys.associateWith { processors.load(it, context) }
        for ((processorClass, elements) in groups) if (!context.cancelled) loaded.getValue(processorClass).process(elements, context)
        context.runCompletionActions()
    }

    /** The debug-mode lines of a dispatch: the descriptor, the elements skipped for want of a plugin and those each processor receives. */
    private fun debug(descriptor: VersatiaPluginDescriptor, skipped: List<Pair<EnhancedElement, List<String>>>, groups: Map<String, List<EnhancedElement>>, context: EnhancementContext) {
        context.debug { "descriptor format ${descriptor.formatMajor}.${descriptor.formatMinor}, ${descriptor.elements.size} elements, ${groups.values.sumOf { it.size }} for this event" }
        for ((element, plugins) in skipped) context.debug { "skipped ${describe(element)}: @ConditionalOnPlugin(${plugins.joinToString()}) is not met" }
        for ((processorClass, elements) in groups) context.debug { "${processorClass.substringAfterLast('.')} receives ${elements.joinToString(transform = ::describe)}" }
    }

    private fun describe(element: EnhancedElement) = when (val target = element.target) {
        is FunctionTarget -> "${target.className.substringAfterLast('.')}.${target.functionName}()"
        is PropertyTarget -> "${target.className.substringAfterLast('.')}.${target.propertyName}"
        else -> target.className.substringAfterLast('.')
    }

    /** One line per missing plugin, telling a plugin that is not installed from one that had not started yet. */
    private fun logSkipped(skipped: List<Pair<EnhancedElement, List<String>>>, context: EnhancementContext) {
        val namesByPlugin = skipped.flatMap { (element, plugins) -> plugins.map { it to skippedName(element, it) } }
            .groupBy({ it.first }, { it.second })
        for ((plugin, names) in namesByPlugin) {
            val reason = if (context.plugin.server.pluginManager.getPlugin(plugin) == null) "$plugin is not installed"
            else "$plugin is installed but was not enabled before ${context.plugin.name}; list it under softdepend: in plugin.yml"
            context.plugin.logger.info("Skipped ${names.distinct().joinToString()}: $reason")
        }
    }

    /** The class when [plugin] is its condition, else the member whose own condition it is: `Class.function()` or `Class.property`. */
    private fun skippedName(element: EnhancedElement, plugin: String): String {
        val target = element.target
        return if (target is MemberTarget && plugin !in conditionPlugins(target.classAnnotations)) describe(element)
        else target.className.substringAfterLast('.')
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

    private fun requiredPlugins(target: ElementTarget) = conditionPlugins(target.annotations + (target as? MemberTarget)?.classAnnotations.orEmpty())

    private fun conditionPlugins(annotations: List<AnnotationOccurrence>) = annotations.filter { it.annotationClass == ConditionalOnPlugin::class.java.name }
        .flatMap { (it.arguments["value"] as? List<*>).orEmpty() }.map { it.toString() }.distinct()

    private companion object {
        val ABSENT_PLUGINS = StateKey<Set<String>>("ConditionalOnPlugin.absentPlugins")
    }
}
