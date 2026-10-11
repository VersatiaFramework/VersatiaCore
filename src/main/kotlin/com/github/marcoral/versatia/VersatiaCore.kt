package com.github.marcoral.versatia

import com.github.marcoral.versatia.di.PluginBean
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Creates the runtime, publishes it as the `VersatiaEngine` service and then enhances itself:
 * VersatiaCore has a descriptor like any plugin, which is how its own commands are hooked in.
 * See docs/runtime.md.
 */
@PluginBean
class VersatiaCore : JavaPlugin() {
    internal lateinit var runtime: VersatiaRuntime
        private set

    override fun onEnable() {
        runtime = VersatiaRuntime()
        server.servicesManager.register(VersatiaEngine::class.java, runtime, this, ServicePriority.Normal)
        runtime.enhance(this)
        logger.info("VersatiaCore has been enabled!")
    }

    override fun onDisable() {
        try {
            runtime.disable(this)
        } finally {
            server.servicesManager.unregisterAll(this)
            runtime.close()
        }
        logger.info("VersatiaCore has been disabled.")
    }
}
