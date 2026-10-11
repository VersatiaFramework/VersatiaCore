package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.ElementTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor

/**
 * Dispatches a plugin's descriptor: the elements whose phase applies to the event and whose
 * `@ConditionalOnPlugin` plugins are enabled, grouped by the processor they name, each group to
 * its processor. Every processor is loaded before any of them runs, so a missing one leaves the
 * plugin untouched. See docs/runtime.md.
 */
class PluginEnhancer(private val processors: ProcessorLoader) {
    fun enhance(descriptor: VersatiaPluginDescriptor, context: DefaultEnhancementContext) {
        val missing = descriptor.elements.filter { it.phase.appliesTo(context.event) }.map { it to missingPlugins(it, context) }
        val skipped = missing.filter { (_, plugins) -> plugins.isNotEmpty() }
        if (skipped.isNotEmpty()) context.plugin.logger.fine {
            "Skipped ${skipped.size} elements whose plugins are missing: " + skipped.joinToString { (element, plugins) -> "${element.target.className} (${plugins.joinToString()})" }
        }
        val groups = missing.filter { (_, plugins) -> plugins.isEmpty() }.map { (element, _) -> element }.groupBy { it.processorClass }
        val loaded = groups.keys.associateWith { processors.load(it, context) }
        for ((processorClass, elements) in groups) loaded.getValue(processorClass).process(elements, context)
        context.runCompletionActions()
    }

    /** The plugins [element] needs that are not enabled: those of its own condition and, for a function, of its class's. */
    private fun missingPlugins(element: EnhancedElement, context: EnhancementContext) =
        requiredPlugins(element.target).filterNot { context.plugin.server.pluginManager.isPluginEnabled(it) }

    private fun requiredPlugins(target: ElementTarget): List<String> {
        val annotations = when (target) {
            is ClassTarget -> target.annotations
            is FunctionTarget -> target.annotations + target.classAnnotations
            is PluginTarget -> target.annotations
        }
        return annotations.filter { it.annotationClass == ConditionalOnPlugin::class.java.name }
            .flatMap { (it.arguments["value"] as? List<*>).orEmpty() }.map { it.toString() }.distinct()
    }
}
