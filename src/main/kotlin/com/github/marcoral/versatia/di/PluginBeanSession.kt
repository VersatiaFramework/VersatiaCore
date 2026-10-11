package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.engine.descriptor.Parameter
import org.bukkit.plugin.Plugin

/**
 * The beans of one enabled plugin: created in dependency order at start, kept in the plugin's
 * state until it is disabled, and used to inject anything else the plugin asks for, such as
 * `@AutoInvoke` parameters. Prototype instances made for this plugin belong to the session too, so
 * they are reloaded with it. See docs/runtime.md.
 */
class PluginBeanSession(
    private val plugin: Plugin,
    private val classLoader: ClassLoader,
    private val exposed: ExposedBeanRegistry,
) : BeanResolver, AutoCloseable {
    private val pluginName get() = plugin.name
    private val definitions = mutableListOf<BeanDefinition>()
    private val prototypeInstances = HashMap<ExposedBean, Any>()
    private val reloadables = LinkedHashSet<Reloadable>()
    private var created: LinkedHashMap<BeanDefinition, Any>? = null
    private var instantiationScheduled = false

    /** Declares beans; they are created once every processor has seen the plugin's start elements. */
    fun declare(context: EnhancementContext, beans: List<BeanDefinition>) {
        if (context.event != LifecycleEvent.START) throw failure("beans can only be declared when the plugin starts, not at ${context.event}")
        if (created != null) throw failure("beans have already been created")
        definitions += beans
        if (!instantiationScheduled) {
            instantiationScheduled = true
            context.onPluginEnhanced {
                val instances = instantiateAll()
                context.plugin.logger.fine { "Created ${instances.size} beans: ${instances.values.joinToString { it.javaClass.name }}" }
            }
        }
    }

    /** Reloads every [Reloadable] of the session in creation order: the plugin's own beans and the prototype instances made for it. */
    fun reload() = reloadables.forEach(::reload)

    /** The instance of the bean class [className], creating all beans first if needed. */
    override fun instanceOf(className: String) = instantiateAll().entries.firstOrNull { it.key.className == className }?.value

    override fun resolve(requester: String, parameters: List<Parameter>) = resolveDependencies(requester, parameters.map(Dependency::of))

    fun resolveDependencies(requester: String, dependencies: List<Dependency>) =
        instantiateAll().let { instances -> dependencies.map { resolve(requester, it, instances::getValue) } }

    /** Creates every declared bean once and exposes those with an exposed scope. */
    fun instantiateAll(): LinkedHashMap<BeanDefinition, Any> {
        created?.let { return it }
        val instances = LinkedHashMap<BeanDefinition, Any>()
        val arguments = HashMap<BeanDefinition, List<Any?>>()
        val inProgress = mutableSetOf<BeanDefinition>()

        fun instanceOf(definition: BeanDefinition): Any {
            instances[definition]?.let { return it }
            if (!inProgress.add(definition)) throw failure("dependency cycle involving ${definition.className}")
            val args = definition.dependencies.map { resolve(definition.className, it, ::instanceOf) }
            val instance = definition.instantiator.create(classLoader, args)
            arguments[definition] = args
            instances[definition] = instance
            inProgress -= definition
            adopt(instance)
            return instance
        }

        definitions.forEach(::instanceOf)

        for (definition in definitions) {
            when (definition.scope) {
                BeanScope.LOCAL -> Unit
                BeanScope.EXPOSED_SINGLETON -> exposed.expose(
                    ExposedBean.Singleton(definition.className, definition.name, definition.primary, definition.assignableTypes, pluginName, instances.getValue(definition)),
                )
                BeanScope.EXPOSED_PROTOTYPE -> {
                    val args = arguments.getValue(definition)
                    val sources = definition.dependencies.map { sourceOf(definition.className, it) }
                    exposed.expose(
                        ExposedBean.Prototype(definition.className, definition.name, definition.primary, definition.assignableTypes, pluginName) { consumer, resolveForConsumer ->
                            val consumerArgs = args.mapIndexed { index, argument ->
                                when (sources[index]) {
                                    ArgumentSource.OWNER -> argument
                                    ArgumentSource.CONSUMER_PLUGIN -> consumerAs(definition, index, consumer)
                                    ArgumentSource.CONSUMER_PROTOTYPE -> resolveForConsumer(definition.dependencies[index])
                                }
                            }
                            definition.instantiator.create(classLoader, consumerArgs)
                        },
                    )
                }
            }
        }
        created = instances
        return instances
    }

    /**
     * What a prototype's constructor argument is when an instance is made for another plugin: the
     * owner's argument, except that the owner's plugin bean becomes the consumer and a prototype,
     * local or exposed, becomes the instance made for the consumer. See docs/runtime.md.
     */
    private fun sourceOf(requester: String, dependency: Dependency): ArgumentSource {
        val local = localProvider(requester, dependency)
        return when {
            local?.name == BeanNames.PLUGIN -> ArgumentSource.CONSUMER_PLUGIN
            local != null -> if (local.scope == BeanScope.EXPOSED_PROTOTYPE) ArgumentSource.CONSUMER_PROTOTYPE else ArgumentSource.OWNER
            exposedProvider(requester, dependency) is ExposedBean.Prototype -> ArgumentSource.CONSUMER_PROTOTYPE
            else -> ArgumentSource.OWNER
        }
    }

    private enum class ArgumentSource { OWNER, CONSUMER_PLUGIN, CONSUMER_PROTOTYPE }

    /**
     * A prototype's plugin parameter receives the plugin the instance is made for; the parameter
     * type must be one every plugin has, which the compile-time step checks for `VersatiaPlugin`s.
     */
    private fun consumerAs(definition: BeanDefinition, index: Int, consumer: Plugin): Plugin {
        val type = definition.dependencies[index].type
        val accepts = runCatching { Class.forName(type, false, classLoader) }.getOrNull()?.isInstance(consumer) == true
        if (!accepts) throw EnhancementException("Plugin ${consumer.name}: ${definition.className} takes its consuming plugin as $type, which ${consumer.javaClass.name} is not")
        return consumer
    }

    /**
     * Local beans first, then exposed ones; a `@Qualifier` filters both sets by name before that
     * preference applies. Among several candidates a single `@Primary` wins. See docs/runtime.md.
     */
    private fun resolve(requester: String, dependency: Dependency, localInstance: (BeanDefinition) -> Any): Any {
        localProvider(requester, dependency)?.let { return localInstance(it) }
        return when (val candidate = exposedProvider(requester, dependency)) {
            is ExposedBean.Singleton -> candidate.instance
            is ExposedBean.Prototype -> prototypeInstances.getOrPut(candidate) {
                adopt(candidate.obtain(plugin) { resolve(candidate.className, it, localInstance) })
            }
        }
    }

    /** The local bean providing [dependency], or null when none does. */
    private fun localProvider(requester: String, dependency: Dependency): BeanDefinition? {
        val qualifier = dependency.qualifier
        val local = definitions.filter { dependency.type in it.assignableTypes && (qualifier == null || it.name == qualifier) }
        return if (local.isEmpty()) null else pick(local, requester, dependency) { it.name to it.primary }
    }

    /** The exposed bean providing [dependency]; throws when none or several do. */
    private fun exposedProvider(requester: String, dependency: Dependency): ExposedBean {
        val qualifier = dependency.qualifier
        val candidates = exposed.candidatesFor(dependency.type).filter { qualifier == null || it.name == qualifier }
        if (candidates.isEmpty()) throw failure("no bean provides $dependency, required by $requester")
        return pick(candidates, requester, dependency) { it.name to it.primary }
    }

    private fun <T : Any> pick(candidates: List<T>, requester: String, dependency: Dependency, describe: (T) -> Pair<String, Boolean>): T {
        if (candidates.size == 1) return candidates.single()
        val primaries = candidates.filter { describe(it).second }
        if (primaries.size == 1) return primaries.single()
        val listed = candidates.joinToString { it.toString() }
        throw failure(
            if (primaries.isEmpty()) "$dependency required by $requester is ambiguous; candidates: $listed. " +
                "Add @Qualifier at the injection point or mark one bean @Primary"
            else "$dependency required by $requester is ambiguous; several candidates are @Primary: $listed",
        )
    }

    /** Takes a new instance into the session: a [Reloadable] is loaded now and on every reload of the plugin. */
    private fun <T : Any> adopt(instance: T): T {
        if (instance is Reloadable && reloadables.add(instance)) reload(instance)
        return instance
    }

    private fun reload(bean: Reloadable) = try {
        bean.reload()
    } catch (e: EnhancementException) {
        throw e
    } catch (e: Exception) {
        throw failure("${bean.javaClass.name} failed to reload", e)
    }

    private fun failure(message: String, cause: Throwable? = null) = EnhancementException("Plugin $pluginName: $message", cause)

    /** Called when the plugin is disabled: whatever it exposed goes with it. */
    override fun close() {
        exposed.removeOwnedBy(pluginName)
    }

    companion object {
        /** The session of the plugin being enhanced, published to other processors as its `BeanResolver`. */
        fun of(context: EnhancementContext) =
            context.state.getOrPut(BeanResolver.KEY) { PluginBeanSession(context.plugin, context.classLoader, ExposedBeanRegistry.of(context)) } as PluginBeanSession
    }
}
