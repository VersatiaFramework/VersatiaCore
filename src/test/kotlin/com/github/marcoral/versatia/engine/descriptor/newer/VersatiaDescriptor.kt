package com.github.marcoral.versatia.engine.descriptor.newer

import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor

/** Mimics a descriptor generated for a future format whose element classes this runtime lacks. */
object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor: Int = 7
    override val formatMinor: Int = 0
    override val elements: List<EnhancedElement>
        get() = throw NoClassDefFoundError("com.github.marcoral.versatia.engine.descriptor/FunctionTarget")
}
