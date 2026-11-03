package dev.versatia.engine.descriptor.sample

import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.VersatiaPluginDescriptor

object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor = VersatiaPluginDescriptor.FORMAT_VERSION.major
    override val formatMinor = VersatiaPluginDescriptor.FORMAT_VERSION.minor
    override val elements: List<EnhancedElement> = emptyList()
}
