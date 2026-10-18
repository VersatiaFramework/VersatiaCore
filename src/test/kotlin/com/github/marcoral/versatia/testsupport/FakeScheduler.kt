package com.github.marcoral.versatia.testsupport

import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask

/**
 * Bukkit's scheduler in tests: records the timers started through it and runs nothing by itself;
 * a test runs a timer's [Timer.task] by hand. A fake plugin's server answers it, and [install]
 * makes it `Bukkit.getScheduler()` too, which the `util.scheduler` helpers call.
 */
object FakeScheduler {
    class Timer(val owner: Plugin, val task: Runnable, val delayTicks: Long, val periodTicks: Long) {
        @Volatile var cancelled = false

        val bukkitTask = Proxy.newProxyInstance(Recorder::class.java.classLoader, arrayOf(BukkitTask::class.java)) { proxy, method, args ->
            when (method.name) {
                "cancel" -> null.also { cancelled = true }
                "isCancelled" -> cancelled
                "getOwner" -> owner
                "isSync" -> true
                "toString" -> "FakeTimer(${owner.name}, every $periodTicks ticks)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> throw UnsupportedOperationException("FakeTimer does not support ${method.name}")
            }
        } as BukkitTask
    }

    val timers = CopyOnWriteArrayList<Timer>()

    val scheduler = Proxy.newProxyInstance(Recorder::class.java.classLoader, arrayOf(BukkitScheduler::class.java)) { _, method, args ->
        when {
            method.name == "runTaskTimer" && args[1] is Runnable ->
                Timer(args[0] as Plugin, args[1] as Runnable, args[2] as Long, args[3] as Long).also { timers += it }.bukkitTask
            else -> throw UnsupportedOperationException("FakeScheduler does not support ${method.name}")
        }
    } as BukkitScheduler

    /** The timers [owner] started, in the order it started them. */
    fun timersOf(owner: Plugin) = timers.filter { it.owner === owner }

    /**
     * Makes Bukkit's static server, once per JVM, one that answers only `getScheduler`. Set by
     * reflection: `Bukkit.setServer` logs the server's build information, which a test lacks.
     */
    @Synchronized
    fun install() {
        val field = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }
        if (field.get(null) != null) return
        val server = Proxy.newProxyInstance(Recorder::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
            when (method.name) {
                "getScheduler" -> scheduler
                "toString" -> "FakeBukkitServer"
                else -> throw UnsupportedOperationException("The Bukkit server of the tests does not support ${method.name}")
            }
        } as Server
        field.set(null, server)
    }
}
