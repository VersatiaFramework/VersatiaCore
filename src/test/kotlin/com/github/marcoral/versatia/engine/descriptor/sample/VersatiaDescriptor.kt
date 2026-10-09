package com.github.marcoral.versatia.engine.descriptor.sample

import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.VersatiaPluginDescriptor

object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor: Int = VersatiaPluginDescriptor.FORMAT_VERSION.major
    override val formatMinor: Int = VersatiaPluginDescriptor.FORMAT_VERSION.minor
    override val elements: List<EnhancedElement> = emptyList()
}
