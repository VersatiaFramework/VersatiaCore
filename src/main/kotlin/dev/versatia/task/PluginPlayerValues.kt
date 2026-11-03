package dev.versatia.task

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bukkit.OfflinePlayer
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin

/**
 * The [PlayerValues] of one plugin's scope: a map by UUID, a quit listener (MONITOR, so the plugin's own quit handlers
 * still read the value) that removes the player's value at once or, with [keepTicks], in a coroutine of [scope] that a
 * join cancels, and a join listener (LOWEST). Both listeners go and the map is cleared when [owner], the scope's job,
 * completes at the plugin's stop. See docs/runtime.md.
 */
internal class PluginPlayerValues<T : Any>(
    private val plugin: Plugin,
    private val scope: CoroutineScope,
    private val keepTicks: Long,
    private val delayTicks: suspend (Long) -> Unit,
    owner: Job,
) : PlayerValues<T>, Listener {
    private val values = ConcurrentHashMap<UUID, T>()
    private val leaving = ConcurrentHashMap<UUID, Job>()

    init {
        val manager = plugin.server.pluginManager
        manager.registerEvent(PlayerQuitEvent::class.java, this, EventPriority.MONITOR, EventExecutor { _, event -> if (event is PlayerQuitEvent) quit(event.player.uniqueId) }, plugin)
        manager.registerEvent(PlayerJoinEvent::class.java, this, EventPriority.LOWEST, EventExecutor { _, event -> if (event is PlayerJoinEvent) leaving.remove(event.player.uniqueId)?.cancel() }, plugin)
        owner.invokeOnCompletion {
            HandlerList.unregisterAll(this)
            values.clear()
        }
    }

    override fun get(player: OfflinePlayer) = values[player.uniqueId]

    override fun set(player: OfflinePlayer, value: T) {
        values[player.uniqueId] = value
    }

    override fun remove(player: OfflinePlayer): T? = values.remove(player.uniqueId)

    override fun contains(player: OfflinePlayer) = values.containsKey(player.uniqueId)

    override fun getOrPut(player: OfflinePlayer, create: () -> T): T = values.computeIfAbsent(player.uniqueId) { create() }

    private fun quit(uuid: UUID) {
        if (keepTicks <= 0) {
            values.remove(uuid)
            return
        }
        if (!values.containsKey(uuid)) return
        val removal = scope.launch(start = CoroutineStart.LAZY) {
            delayTicks(keepTicks)
            values.remove(uuid)
        }
        removal.invokeOnCompletion { leaving.remove(uuid, removal) }
        leaving.put(uuid, removal)?.cancel()
        removal.start()
    }
}
