package dev.versatia.task

import dev.versatia.Phase
import dev.versatia.di.LocalBean
import dev.versatia.lifecycle.AutoInvoke
import dev.versatia.util.plugin.logWarning
import dev.versatia.util.scheduler.runTaskTimer
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
