package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.engine.descriptor.occurrenceOf

enum class BeanScope { LOCAL, EXPOSED_SINGLETON, EXPOSED_PROTOTYPE }

/** Something to inject: a type and, optionally, the name of the bean that must provide it. */
data class Dependency(val type: String, val qualifier: String? = null) {
    override fun toString() = if (qualifier == null) type else "$type named \"$qualifier\""

    companion object {
        fun of(parameter: Parameter) = Dependency(parameter.type, parameter.annotations.qualifier())
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
