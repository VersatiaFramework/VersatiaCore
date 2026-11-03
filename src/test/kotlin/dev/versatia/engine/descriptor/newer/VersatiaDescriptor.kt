package dev.versatia.engine.descriptor.newer

import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.VersatiaPluginDescriptor

/** Mimics a descriptor generated for a future format whose element classes this runtime lacks. */
object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor = 7
    override val formatMinor = 0
    override val elements: List<EnhancedElement>
        get() = throw NoClassDefFoundError("dev.versatia.engine.descriptor/FunctionTarget")
}
