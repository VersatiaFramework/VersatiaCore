package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.StateKey
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.debug
import com.github.marcoral.versatia.failure
import com.github.marcoral.versatia.receiverOf
import com.github.marcoral.versatia.targetAs
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.di.ExposedBeanRegistry
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.resources.ExtractResources
import com.github.marcoral.versatia.util.config.ConfigurationValueException
import com.github.marcoral.versatia.util.config.loadYamlOrThrow
import org.bukkit.configuration.ConfigurationSection

/**
 * Sets `@Value` properties from the YAML files of the plugin's data folder. It works in `process`
 * itself, so the values are in place before any completion action: before the plugin's
 * `Reloadable` beans are reloaded and before the `@AutoInvoke` functions run. The descriptor lists
 * the properties after the beans, so at start the beans are declared and the files extracted by
 * then. Every value is read before any property is set, so a broken file leaves the plugin as it
 * was. The values of the main class and of objects are set at once. Those of beans go to every
 * instance, the plugin's own and each one its prototypes make for other plugins: at start they
 * are kept and handed to the bean session, which supplies a constructor property, as a new filled
 * cell for a `PreviousValueTracking`, and sets the body properties right after the constructor,
 * so every instance, however late it is made, starts with the values current then; a bean
 * constructor sees the values of the main class, of objects and of the beans it takes. At a
 * reload the values replace the kept ones and are set on every live instance. The bean classes are
 * marked in the exposed-bean registry, so a reload of the plugin reloads the plugins holding them,
 * and only those. Every receiver is resolved before the first property is set, and a file path
 * that leaves the data folder is refused. See docs/runtime.md.
 */
class ValueEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val bindings = elements.map { element ->
            val target = element.targetAs<PropertyTarget>(this)
            val arguments = element.annotation.arguments
            Binding(target, arguments["file"] as String, arguments["key"] as String, (arguments["mapper"] as? String)?.takeIf { it != NO_MAPPER })
        }
        val files = bindings.map { it.file }.distinct().associateWith { load(it, context) }
        val reader = ValueReader(context.classLoader)
        val values = bindings.map { it to read(it, reader, files.getValue(it.file), context) }
        val (beanValues, otherValues) = values.partition { (binding, _) -> binding.target.owner == MemberOwner.INSTANCE }
        val starting = context.event == LifecycleEvent.START
        val assignments = otherValues.map { (binding, value) -> Assignment(binding, context.receiverOf(binding.target), value) } +
            if (starting) emptyList() else beanValues.flatMap { (binding, value) -> instancesOf(binding, context).map { Assignment(binding, it, value) } }
        val current = context.state.getOrPut(CURRENT) { HashMap() }
        values.forEach { (binding, value) -> current[binding.description] = value }
        assignments.forEach { assign(it.binding, it.receiver, it.value, context) }
        if (starting) prepare(beanValues.map { it.first }, current, context)
        context.debug { "set ${values.size} values, ${bindings.groupBy({ it.file }, { it.key }).entries.joinToString("; ") { (file, keys) -> "$file: ${keys.joinToString()}" }}" }
    }

    private class Binding(val target: PropertyTarget, val file: String, val key: String, val mapper: String?) {
        val description get() = target.description
    }

    private class Assignment(val binding: Binding, val receiver: Any, val value: Any?)

    private fun load(file: String, context: EnhancementContext): ConfigurationSection {
        val dataFolder = context.plugin.dataFolder.toPath().toAbsolutePath().normalize()
        val path = dataFolder.resolve(file).normalize().toFile()
        if (!path.toPath().startsWith(dataFolder) || path.toPath() == dataFolder)
            throw context.failure("the @Value file \"$file\" lies outside the data folder; name a path relative to it, such as config.yml")
        if (!path.isFile) missingFileMessage(file, context).let { throw context.failure(it, ConfigurationValueException(it)) }
        return try {
            loadYamlOrThrow(path)
        } catch (e: Exception) {
            throw context.failure("$file could not be read", e)
        }
    }

    /** A hint for the server admin when the JAR carries a default copy, for the plugin's developer otherwise. */
    private fun missingFileMessage(file: String, context: EnhancementContext) =
        if (context.classLoader.getResource("${ExtractResources.DIRECTORY}/$file") != null) {
            "$file is missing from the data folder; put it back, or reload the plugin to restore the default copy from the plugin JAR"
        } else {
            "$file is missing from the data folder and the plugin JAR has no default copy; create it, or, as the plugin's developer, " +
                "ship it as ${ExtractResources.DIRECTORY}/$file in the JAR, which is extracted at start and reload unless @DisableVersatiaFeatures(extractResources = true) is set"
        }

    private fun read(binding: Binding, reader: ValueReader, file: ConfigurationSection, context: EnhancementContext) = try {
        reader.read(file, binding.key, binding.target.type, binding.mapper)
    } catch (e: Exception) {
        throw context.failure("${binding.description} could not be read from ${binding.file}: ${reasonOf(e)}", e)
    } catch (e: LinkageError) {
        throw context.failure("${binding.description} could not be read from ${binding.file}: ${reasonOf(e)}", e)
    }

    /**
     * Hands the bean values to the session for every instance it makes from now on: constructor
     * properties as supplied arguments, body properties set right after the constructor. Both read
     * [current], so an instance made after a reload starts with the values of that reload.
     */
    private fun prepare(bindings: List<Binding>, current: Map<String, Any?>, context: EnhancementContext) {
        if (bindings.isEmpty()) return
        val beans = context.state[BeanResolver.KEY] ?: throw noBean(bindings.first(), context)
        val exposed = ExposedBeanRegistry.of(context)
        bindings.map { it.target.className }.distinct().forEach { exposed.followOwnerReloads(context.plugin.name, it) }
        val (passed, assigned) = bindings.partition { it.target.constructorParameter != null }
        for (binding in passed) beans.supply(binding.target.className, binding.target.constructorParameter!!) {
            val value = current[binding.description]
            if (binding.target.type.name == TRACKING) PreviousValueTracking<Any?>().also { it.value = value } else value
        }
        for ((className, properties) in assigned.groupBy { it.target.className }) {
            val prepared = beans.onInstanceCreated(className) { instance -> properties.forEach { assign(it, instance, current[it.description], context) } }
            if (!prepared) throw noBean(properties.first(), context)
        }
    }

    private fun instancesOf(binding: Binding, context: EnhancementContext) =
        context.state[BeanResolver.KEY]?.instancesOf(binding.target.className).orEmpty()
            .ifEmpty { throw noBean(binding, context) }

    private fun noBean(binding: Binding, context: EnhancementContext) = context.failure("no bean ${binding.target.className} exists to set ${binding.description} on")

    private fun assign(binding: Binding, instance: Any, value: Any?, context: EnhancementContext) {
        try {
            binding.target.setter.set(context.classLoader, instance, value)
        } catch (e: Exception) {
            throw context.failure("${binding.description} could not be set: ${reasonOf(e)}", e)
        } catch (e: LinkageError) {
            throw context.failure("${binding.description} could not be set: ${reasonOf(e)}", e)
        }
    }

    private fun reasonOf(e: Throwable) = e.message ?: e.javaClass.name

    private companion object {
        val NO_MAPPER: String = NoMapper::class.java.name
        val TRACKING = PreviousValueTracking::class.qualifiedName


        /** The values last read, by property: what an instance made later starts with. */
        val CURRENT = StateKey<MutableMap<String, Any?>>("Value.current")
    }
}
