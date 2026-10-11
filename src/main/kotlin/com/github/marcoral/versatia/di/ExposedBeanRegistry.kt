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
    class Singleton(
        className: String,
        name: String,
        primary: Boolean,
        assignableTypes: List<String>,
        owner: String,
        val instance: Any,
    ) : ExposedBean(className, name, primary, assignableTypes, owner)

    /**
     * Makes an instance for a consuming plugin; the factory resolves the arguments that depend on
     * the consumer through `resolve`, the consumer's own dependency resolution.
     */
    class Prototype(
        className: String,
        name: String,
        primary: Boolean,
        assignableTypes: List<String>,
        owner: String,
        private val factory: (consumer: Plugin, resolve: (Dependency) -> Any) -> Any,
    ) : ExposedBean(className, name, primary, assignableTypes, owner) {
        fun obtain(consumer: Plugin, resolve: (Dependency) -> Any) = factory(consumer, resolve)
    }

    override fun toString() = "\"$name\" ($className from $owner)"
}
