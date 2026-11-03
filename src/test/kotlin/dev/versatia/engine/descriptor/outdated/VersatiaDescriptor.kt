package dev.versatia.engine.descriptor.outdated

import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.VersatiaPluginDescriptor

object VersatiaDescriptor : VersatiaPluginDescriptor {
    override val formatMajor = 0
    override val formatMinor = 9
    override val elements: List<EnhancedElement> = emptyList()
}
