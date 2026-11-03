package dev.versatia

import dev.versatia.config.Value
import dev.versatia.di.PluginBean
import dev.versatia.engine.descriptor.DescriptorLoader
import org.bukkit.plugin.ServicePriority
import dev.versatia.resources.ExtractResources
import org.bukkit.plugin.java.JavaPlugin

/**
 * Creates the runtime, publishes it as the `VersatiaEngine` service and then enhances itself:
 * VersatiaCore has a descriptor like any plugin, which is how its own commands and its
 * config.yml, extracted from the JAR and read at start and reload, are hooked in. See docs/runtime.md.
 */
@PluginBean
@ExtractResources
class VersatiaCore : JavaPlugin() {
    internal lateinit var runtime: VersatiaRuntime
        private set

    @Value("debug")
    private var debug = false

    override fun onEnable() {
        runtime = VersatiaRuntime(debugLog = DebugLog(logger) { debug })
        server.servicesManager.register(VersatiaEngine::class.java, runtime, this, ServicePriority.Normal)
        runtime.enhance(this)
        logger.info("Runtime ready, reading descriptor format ${DescriptorLoader.supportedFormat}")
    }

    override fun onDisable() {
        try {
            runtime.disable(this)
        } finally {
            server.servicesManager.unregisterAll(this)
            runtime.close()
        }
    }
}
