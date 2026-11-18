package dev.versatia.di

import dev.versatia.StateKey
import dev.versatia.messages.Messages
import dev.versatia.task.PluginScope
import dev.versatia.text.Text
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import org.bukkit.plugin.Plugin

/**
 * The default beans every running plugin also has at hand on the plugin itself, as `plugin.text`,
 * `plugin.messages` and `plugin.scope`: the instances its beans are injected, published by its
 * [PluginBeanSession] once the beans exist and withdrawn when the session closes. Read from any
 * thread.
 */
class DefaultBeans {
    private val byPlugin = ConcurrentHashMap<Plugin, Map<Class<*>, Any>>()

    fun publish(plugin: Plugin, beans: Map<Class<*>, Any>) {
        byPlugin[plugin] = beans
    }

    fun withdraw(plugin: Plugin) {
        byPlugin.remove(plugin)
    }

    fun <T : Any> of(plugin: Plugin, type: KClass<T>): T = type.java.cast(
        byPlugin[plugin]?.get(type.java) ?: error(
            "Plugin ${plugin.name} has no ${type.simpleName} at this point: plugin.${TYPES[type.java]} is there while a Versatia plugin runs, " +
                "from the moment its beans exist until it stops. In a constructor or a property initializer, take ${type.simpleName} as a constructor parameter instead.",
        ),
    )

    companion object {
        val KEY = StateKey<DefaultBeans>("default-beans")

        /** The types, each with the name of the property that gives it. */
        val TYPES = mapOf(Text::class.java to "text", Messages::class.java to "messages", PluginScope::class.java to "scope")
    }
}
