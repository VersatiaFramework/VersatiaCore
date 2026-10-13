package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.PropertyTarget
import com.github.marcoral.versatia.engine.descriptor.receiverOf
import com.github.marcoral.versatia.resources.ExtractResources
import com.github.marcoral.versatia.util.config.loadYamlOrThrow
import org.bukkit.configuration.ConfigurationSection

/**
 * Sets `@Value` properties from the YAML files of the plugin's data folder. It works in `process`
 * itself, so the values are in place before any completion action: before the plugin's
 * `Reloadable` beans are reloaded and before the `@AutoInvoke` functions run. The descriptor lists
 * the properties after the beans, so at start the beans are declared and the files extracted by
 * then; a property of a bean makes the session create the beans. Every value is read before any
 * property is set, so a broken file leaves the plugin as it was. See docs/runtime.md.
 */
class ValueEnhancementProcessor : EnhancementProcessor {
    private val noMapper = NoMapper::class.java.name

    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val bindings = elements.map { element ->
            val target = element.target as? PropertyTarget
                ?: throw EnhancementException("${javaClass.name} can only enhance properties, got ${element.target}")
            val arguments = element.annotation.arguments
            Binding(target, arguments["file"] as String, arguments["key"] as String, (arguments["mapper"] as? String)?.takeIf { it != noMapper })
        }
        val files = bindings.map { it.file }.distinct().associateWith { load(it, context) }
        val values = bindings.map { it to read(it, files.getValue(it.file), context) }
        values.forEach { (binding, value) -> assign(binding, value, context) }
        context.plugin.logger.fine { "Set ${values.size} values from ${files.keys.joinToString()}" }
    }

    private class Binding(val target: PropertyTarget, val file: String, val key: String, val mapper: String?) {
        val description get() = "${target.className}.${target.propertyName}"
    }

    private fun load(file: String, context: EnhancementContext): ConfigurationSection {
        val path = context.plugin.dataFolder.resolve(file)
        if (!path.isFile) throw failure(context, "$file does not exist in the data folder; ship it under ${ExtractResources.DIRECTORY}/ in the JAR")
        return try {
            loadYamlOrThrow(path)
        } catch (e: Exception) {
            throw failure(context, "$file could not be read", e)
        }
    }

    private fun read(binding: Binding, file: ConfigurationSection, context: EnhancementContext) = try {
        ValueReader(context.classLoader).read(file, binding.key, binding.target.type, binding.mapper)
    } catch (e: EnhancementException) {
        throw e
    } catch (e: Exception) {
        throw failure(context, "${binding.description} could not be read from ${binding.file}: ${e.message}", e)
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
