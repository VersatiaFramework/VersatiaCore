package dev.versatia

import dev.versatia.di.ExposeSingleton

/** Exposes the runtime's registry to every plugin as an injectable `PluginRegistry`. */
@ExposeSingleton
class PluginRegistryBean(core: VersatiaCore) : PluginRegistry by core.runtime.registry
