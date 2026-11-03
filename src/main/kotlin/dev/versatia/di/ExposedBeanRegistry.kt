package dev.versatia.di

import dev.versatia.EnhancementContext
import dev.versatia.StateKey
import org.bukkit.plugin.Plugin

/**
 * Beans shared between plugins. Lives in the runtime state; each entry lives as long as the plugin
 * that exposed it and records the plugins that obtained it, with the instance made for each from a
 * prototype, until they are disabled. A processor marks the bean classes whose state follows the
 * owner's reloads ([followOwnerReloads]); those records tell a reload which plugins follow it and
 * which instances take the owner's values. See docs/runtime.md.
 */
class ExposedBeanRegistry {
    private val beans = mutableListOf<ExposedBean>()
    private val following = HashMap<String, MutableSet<String>>()
    private val dependencies = HashMap<String, MutableMap<String, Set<String>>>()

    fun expose(bean: ExposedBean) {
        beans += bean
    }

    fun candidatesFor(type: String) = beans.filter { type in it.assignableTypes }

    /** The beans [owner] exposes, in the order it exposed them. */
    fun ownedBy(owner: String) = beans.filter { it.owner == owner }

    fun removeOwnedBy(owner: String) {
        beans.removeAll { it.owner == owner }
        following.remove(owner)
        dependencies.remove(owner)
    }

    /** The plugins holding a bean [owner] exposes, by name. */
    fun consumersOf(owner: String) = consumersOf(owner) { true }

    /** Marks the bean class [className] of [owner] as one whose state a reload of [owner] changes, such as one with `@Value` properties. */
    fun followOwnerReloads(owner: String, className: String) {
        following.getOrPut(owner) { HashSet() } += className
    }

    /** Records the beans of [owner] the bean class [className] takes in its constructor, by class name. */
    fun recordDependencies(owner: String, className: String, dependsOn: Set<String>) {
        dependencies.getOrPut(owner) { HashMap() }[className] = dependsOn
    }

    /**
     * The plugins holding a bean of [owner] that follows its reloads, by name: those a reload of
     * [owner] reloads too. A bean follows when its class is marked with [followOwnerReloads] or it
     * takes, directly or through other beans of [owner], one that is.
     */
    fun consumersReloadedWith(owner: String) = consumersOf(owner) { follows(owner, it.className) }

    private fun follows(owner: String, className: String, seen: MutableSet<String> = HashSet()): Boolean =
        className in following[owner].orEmpty() || seen.add(className) && dependencies[owner]?.get(className).orEmpty().any { follows(owner, it, seen) }

    private fun consumersOf(owner: String, filter: (ExposedBean) -> Boolean) =
        beans.filter { it.owner == owner && filter(it) }.flatMapTo(LinkedHashSet()) { it.consumers } - owner

    /** Forgets what [consumer] obtained, when it is disabled. */
    fun release(consumer: String) = beans.forEach { it.release(consumer) }

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
    private val users = LinkedHashSet<String>()

    /** The plugins that obtained this bean, by name. */
    val consumers: Set<String> get() = users

    protected fun usedBy(consumer: Plugin) {
        users += consumer.name
    }

    open fun release(consumer: String) {
        users -= consumer
    }

    class Singleton(
        className: String,
        name: String,
        primary: Boolean,
        assignableTypes: List<String>,
        owner: String,
        val instance: Any,
    ) : ExposedBean(className, name, primary, assignableTypes, owner) {
        fun obtain(consumer: Plugin) = instance.also { usedBy(consumer) }
    }

    /**
     * Makes an instance for a consuming plugin; the factory resolves the arguments that depend on
     * the consumer through `resolve`, the consumer's own dependency resolution. Each instance is
     * kept, with its consumer, until that consumer is disabled.
     */
    class Prototype(
        className: String,
        name: String,
        primary: Boolean,
        assignableTypes: List<String>,
        owner: String,
        private val factory: (consumer: Plugin, resolve: (Dependency) -> Any) -> Any,
    ) : ExposedBean(className, name, primary, assignableTypes, owner) {
        private val made = mutableListOf<Pair<String, Any>>()

        /** The instances made for plugins that are still running. */
        val instances get() = made.map { it.second }

        fun obtain(consumer: Plugin, resolve: (Dependency) -> Any) = factory(consumer, resolve).also {
            usedBy(consumer)
            made += consumer.name to it
        }

        override fun release(consumer: String) {
            super.release(consumer)
            made.removeAll { it.first == consumer }
        }
    }

    override fun toString() = "\"$name\" ($className from $owner)"
}
