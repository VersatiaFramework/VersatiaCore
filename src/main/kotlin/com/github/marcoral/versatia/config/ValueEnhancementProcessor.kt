package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.engine.descriptor.receiverOf
import com.github.marcoral.versatia.resources.ExtractResources
import com.github.marcoral.versatia.util.config.ConfigurationValueException
import com.github.marcoral.versatia.util.config.loadYamlOrThrow
import org.bukkit.configuration.ConfigurationSection

/**
 * Sets `@Value` properties from the YAML files of the plugin's data folder. It works in `process`
 * itself, so the values are in place before any completion action: before the plugin's
 * `Reloadable` beans are reloaded and before the `@AutoInvoke` functions run. The descriptor lists
 * the properties after the beans, so at start the beans are declared and the files extracted by
 * then; a property of a bean makes the session create the beans. Every value is read before any
 * property is set, so a broken file leaves the plugin as it was. At start, the value of a property
 * declared in a bean's constructor is supplied to that constructor, as a filled cell for a
 * `PreviousValueTracking`, instead of being set; at a reload it is set like any other. See
 * docs/runtime.md.
 */
class ValueEnhancementProcessor : EnhancementProcessor {
    private val noMapper = NoMapper::class.java.name
    private val tracking = PreviousValueTracking::class.qualifiedName

    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val bindings = elements.map { element ->
            val target = element.target as? PropertyTarget
                ?: throw EnhancementException("${javaClass.name} can only enhance properties, got ${element.target}")
            val arguments = element.annotation.arguments
            Binding(target, arguments["file"] as String, arguments["key"] as String, (arguments["mapper"] as? String)?.takeIf { it != noMapper })
        }
        val files = bindings.map { it.file }.distinct().associateWith { load(it, context) }
        val values = bindings.map { it to read(it, files.getValue(it.file), context) }
        val (passed, assigned) = values.partition { (binding, _) -> context.event == LifecycleEvent.START && binding.target.constructorParameter != null }
        if (passed.isNotEmpty()) supply(passed, context)
        assigned.forEach { (binding, value) -> assign(binding, value, context) }
        context.plugin.logger.fine { "Set ${values.size} values from ${files.keys.joinToString()}" }
    }

    private class Binding(val target: PropertyTarget, val file: String, val key: String, val mapper: String?) {
        val description get() = "${target.className}.${target.propertyName}"
    }

    private fun load(file: String, context: EnhancementContext): ConfigurationSection {
        val path = context.plugin.dataFolder.resolve(file)
        if (!path.isFile) missingFileMessage(file, context).let { throw failure(context, it, ConfigurationValueException(it)) }
        return try {
            loadYamlOrThrow(path)
        } catch (e: Exception) {
            throw failure(context, "$file could not be read", e)
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

    private fun read(binding: Binding, file: ConfigurationSection, context: EnhancementContext) = try {
        ValueReader(context.classLoader).read(file, binding.key, binding.target.type, binding.mapper)
    } catch (e: EnhancementException) {
        throw e
    } catch (e: Exception) {
        throw failure(context, "${binding.description} could not be read from ${binding.file}: ${e.message}", e)
    }

    /** Hands the values of constructor properties to the bean session, which passes them on when it creates the beans. */
    private fun supply(values: List<Pair<Binding, Any?>>, context: EnhancementContext) {
        val beans = context.state[BeanResolver.KEY]
            ?: throw failure(context, "${values.first().first.description} is declared in a bean constructor, but the plugin has no beans")
        for ((binding, value) in values) binding.target.constructorParameter?.let { index ->
            val argument = if (binding.target.type.name == tracking) PreviousValueTracking<Any?>().also { it.value = value } else value
            beans.supply(binding.target.className, index, argument)
        }
    }

    private fun assign(binding: Binding, value: Any?, context: EnhancementContext) {
        val target = binding.target
        val instance = context.receiverOf(target.owner, target.className, target.binaryClassName, "set ${binding.description} on")
        try {
            target.setter.set(context.classLoader, instance, value)
        } catch (e: EnhancementException) {
            throw e
        } catch (e: Exception) {
            throw failure(context, "${binding.description} could not be set", e)
        }
    }

    private fun failure(context: EnhancementContext, message: String, cause: Throwable? = null) =
        EnhancementException("Plugin ${context.plugin.name}: $message", cause)
}
