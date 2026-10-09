package com.github.marcoral.versatia

import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor

/**
 * Dispatches a plugin's descriptor: the elements whose phase applies to the event, grouped by the
 * processor they name, each group to its processor. Every processor is loaded before any of them
 * runs, so a missing one leaves the plugin untouched. See docs/runtime.md.
 */
class PluginEnhancer(private val processors: ProcessorLoader) {
    fun enhance(descriptor: VersatiaPluginDescriptor, context: DefaultEnhancementContext) {
        val groups = descriptor.elements.filter { it.phase.appliesTo(context.event) }.groupBy { it.processorClass }
        val loaded = groups.keys.associateWith { processors.load(it, context) }
        for ((processorClass, elements) in groups) loaded.getValue(processorClass).process(elements, context)
        context.runCompletionActions()
    }
}
