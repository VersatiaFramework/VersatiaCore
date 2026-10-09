package com.github.marcoral.versatia.engine.descriptor.outdated

import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor

object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor: Int = 0
    override val formatMinor: Int = 9
    override val elements: List<EnhancedElement> = emptyList()
}
