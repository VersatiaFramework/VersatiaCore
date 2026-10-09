package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.StateKey
import org.bukkit.plugin.Plugin

/**
 * Beans shared between plugins. Lives in the runtime state; each entry lives as long as the plugin
 * that exposed it.
 */
class ExposedBeanRegistry {
    private val beans = mutableListOf<ExposedBean>()

    fun expose(bean: ExposedBean) {
        beans += bean
    }

    fun candidatesFor(type: String) = beans.filter { type in it.assignableTypes }

    fun removeOwnedBy(owner: String) = beans.removeAll { it.owner == owner }

    val size get() = beans.size

    companion object {
        val KEY = StateKey<ExposedBeanRegistry>("exposed-beans")

        fun of(context: EnhancementContext) = context.runtimeState.getOrPut(KEY) { ExposedBeanRegistry() }
    }
}

sealed class ExposedBean(
    val className: String,
    val name: String,
    val primary: Boolean,
    val assignableTypes: List<String>,
    val owner: String,
) {
    /** The instance for [consumer], the plugin asking for the bean. */
    abstract fun obtain(consumer: Plugin): Any

    class Singleton(
        className: String,
        name: String,
        primary: Boolean,
        assignableTypes: List<String>,
        owner: String,
        private val instance: Any,
    ) : ExposedBean(className, name, primary, assignableTypes, owner) {
        override fun obtain(consumer: Plugin) = instance
    }

    class Prototype(
        className: String,
        name: String,
        primary: Boolean,
        assignableTypes: List<String>,
        owner: String,
        private val factory: (Plugin) -> Any,
    ) : ExposedBean(className, name, primary, assignableTypes, owner) {
        override fun obtain(consumer: Plugin) = factory(consumer)
    }

    override fun toString() = "\"$name\" ($className from $owner)"
}
