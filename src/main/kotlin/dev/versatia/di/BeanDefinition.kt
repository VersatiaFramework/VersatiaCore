package dev.versatia.di

import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.ClassTarget
import dev.versatia.engine.descriptor.Instantiator
import dev.versatia.engine.descriptor.Parameter
import dev.versatia.engine.descriptor.PluginTarget
import dev.versatia.engine.descriptor.occurrenceOf

enum class BeanScope(val label: String) { LOCAL("local"), EXPOSED_SINGLETON("exposed singleton"), EXPOSED_PROTOTYPE("exposed prototype") }

/**
 * Something to inject: a type and, optionally, the name of the bean that must provide it. An
 * [optional] one keeps its default value when no bean provides it; one with [suppliedBy] is not
 * injected at all, that processor supplies the argument.
 */
data class Dependency(val type: String, val qualifier: String? = null, val optional: Boolean = false, val suppliedBy: String? = null) {
    override fun toString() = if (qualifier == null) type else "$type named \"$qualifier\""

    companion object {
        fun of(parameter: Parameter) = Dependency(parameter.type, parameter.annotations.qualifier(), parameter.optional, parameter.suppliedBy)
    }
}

private val QUALIFIER = Qualifier::class.java.name
private val PRIMARY = Primary::class.java.name

/** The value of `@Qualifier` among recorded attributes, or null when absent. */
internal fun List<AnnotationOccurrence>.qualifier() = occurrenceOf(QUALIFIER)?.arguments?.get("value") as? String

internal fun List<AnnotationOccurrence>.isPrimary() = occurrenceOf(PRIMARY) != null

/**
 * What the scope processors make of a descriptor element: a bean to create, or the plugin instance
 * to adopt. Name, `@Primary` and qualifiers are read from the recorded attributes here, so the
 * descriptor itself knows nothing about beans; see docs/runtime.md.
 */
class BeanDefinition(
    val scope: BeanScope,
    val className: String,
    val name: String,
    val primary: Boolean,
    val assignableTypes: List<String>,
    val dependencies: List<Dependency>,
    val instantiator: Instantiator,
) {
    override fun toString() = "\"$name\" ($className)"

    companion object {
        fun of(scope: BeanScope, target: ClassTarget) = BeanDefinition(
            scope = scope,
            className = target.className,
            name = target.annotations.qualifier() ?: BeanNames.defaultFor(target.className.substringAfterLast('.')),
            primary = target.annotations.isPrimary(),
            assignableTypes = target.assignableTypes,
            dependencies = target.constructorParameters.map(Dependency::of),
            instantiator = target.instantiator,
        )

        /** The plugin's main class: Bukkit created [instance], so the instantiator just returns it. */
        fun plugin(target: PluginTarget, instance: Any) = BeanDefinition(
            scope = BeanScope.LOCAL,
            className = target.className,
            name = BeanNames.PLUGIN,
            primary = target.annotations.isPrimary(),
            assignableTypes = target.assignableTypes,
            dependencies = emptyList(),
            instantiator = Instantiator.Direct { instance },
        )
    }
}
