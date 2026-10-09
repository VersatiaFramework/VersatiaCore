package com.github.marcoral.versatia

import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

class VersatiaCore : JavaPlugin() {
    private lateinit var runtime: VersatiaRuntime

    override fun onEnable() {
        runtime = VersatiaRuntime()
        server.servicesManager.register(VersatiaEngine::class.java, runtime, this, ServicePriority.Normal)
        logger.info("VersatiaCore has been enabled!")
    }

    override fun onDisable() {
        server.servicesManager.unregisterAll(this)
        runtime.close()
        logger.info("VersatiaCore has been disabled.")
    }
}
