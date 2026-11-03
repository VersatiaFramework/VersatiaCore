package dev.versatia.task

import dev.versatia.StateKey
import dev.versatia.util.plugin.log
import dev.versatia.util.plugin.logSevere
import dev.versatia.util.plugin.logWarning
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.plugin.Plugin

/** The plugin's background tasks and their runs; the scope cancels the runs at stop. */
internal class BackgroundTasks(private val plugin: Plugin, private val scope: PluginScopeBean, tasks: List<BackgroundTask>) {
    private val runs = tasks.associateWith { ConcurrentHashMap.newKeySet<Job>() }

    fun start() {
        runs.keys.forEach(::launch)
        plugin.log(Level.FINE) { "Started ${runs.size} background tasks: ${runs.keys.joinToString { it.javaClass.name }}" }
    }

    /** Cancels the running runs, whose finally blocks run now, and launches new ones that first wait for them. */
    fun restart() {
        val reason = CancellationException("Plugin ${plugin.name} reloaded")
        runs.values.flatMap { it.toList() }.forEach { it.cancel(reason) }
        runs.keys.forEach(::launch)
        plugin.log(Level.FINE) { "Restarted ${runs.size} background tasks" }
    }

    private fun launch(task: BackgroundTask) {
        val ofTask = runs.getValue(task)
        val previous = ofTask.toList()
        val name = task.javaClass.name
        val run = scope.launch(CoroutineName(name), CoroutineStart.LAZY) { execute(task, name, previous) }
        ofTask += run
        run.invokeOnCompletion { ofTask -= run }
        run.start()
    }

    private suspend fun CoroutineScope.execute(task: BackgroundTask, name: String, previous: List<Job>) {
        if (withTimeoutOrNull(scope.closeTimeout) { previous.joinAll() } == null) {
            plugin.logWarning("Background task $name is still finishing its previous run ${scope.closeTimeout} after the reload cancelled it; the new run starts when it has")
            previous.joinAll()
        }
        delayTicks(1)
        try {
            task.run()
            plugin.log(Level.FINE) { "Background task $name finished" }
        } catch (e: CancellationException) {
            if (isActive) plugin.logger.log(Level.WARNING, "Background task $name ended with ${e.javaClass.simpleName} although nobody cancelled it; it starts again at the next reload", e)
            throw e
        } catch (e: Exception) {
            plugin.logSevere("Background task $name failed; it starts again at the next reload", e)
        }
    }

    companion object {
        val KEY = StateKey<BackgroundTasks>("backgroundTasks")
    }
}
