package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.di.LocalBean
import com.github.marcoral.versatia.lifecycle.AutoInvoke
import com.github.marcoral.versatia.util.plugin.logWarning
import com.github.marcoral.versatia.util.scheduler.runTaskTimer
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/** The one Bukkit timer of the server driving the TickLoop; it lives as long as VersatiaCore. */
@LocalBean
internal class ServerTicker(private val core: Plugin) : AutoCloseable {
    val loop = TickLoop({ core.server.isPrimaryThread })
    private var heartbeat: BukkitTask? = null

    @AutoInvoke(Phase.PLUGIN_START_ONLY)
    fun start() {
        heartbeat = core.runTaskTimer(periodTicks = 1) { loop.heartbeat() }
    }

    override fun close() {
        heartbeat?.cancel()
        val unfinished = loop.stop()
        if (unfinished.isNotEmpty()) core.logWarning("Coroutines of ${unfinished.joinToString { it.plugin.name }} were still running when VersatiaCore stopped; they are cancelled and finish on a kotlinx thread")
    }
}
