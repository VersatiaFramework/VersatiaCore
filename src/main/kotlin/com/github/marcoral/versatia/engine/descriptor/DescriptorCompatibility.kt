package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementException

/** A runtime interprets exactly the descriptor format it was built with; see docs/runtime.md. */
object DescriptorCompatibility {
    fun check(pluginName: String, descriptor: DescriptorVersion, supported: DescriptorVersion) {
        if (descriptor == supported) return
        val fix = if (descriptor < supported) "Rebuild the plugin against the VersatiaAPI matching this VersatiaCore."
        else "Update VersatiaCore, or rebuild the plugin against the VersatiaAPI matching it."
        throw EnhancementException(
            "$pluginName was built for descriptor format $descriptor, but this VersatiaCore supports $supported. $fix The plugin stays disabled.",
        )
    }
}
