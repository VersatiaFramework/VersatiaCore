package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.StateKey
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.failure
import com.github.marcoral.versatia.inject
import com.github.marcoral.versatia.targetAs

/**
 * The instance a subclass-enhancement element stands for: the plugin for its PluginTarget, the
 * bean of the class when the plugin has one, otherwise the class's instance in [SubclassInstances].
 */
internal fun EnhancementContext.subclassInstanceOf(element: EnhancedElement, processor: EnhancementProcessor): Any = when (val target = element.target) {
    is PluginTarget -> plugin
    is ClassTarget -> state[BeanResolver.KEY]?.instanceOf(target.className) ?: SubclassInstances.of(this).instanceOf(target, this)
    is MemberTarget -> element.targetAs<ClassTarget>(processor)
}

/**
 * The instances of the plugin's classes that subclass enhancements create without being beans:
 * one per class, made through the element's instantiator with its constructor parameters
 * injected and shared by every feature enhancing the class, so a plain class that is a Listener
 * and a BackgroundTask exists once. A processor supplying constructor arguments, such as the
 * `@Value` processor, does so before the instance is made, and may ask to prepare the instance
 * right after its constructor. See docs/runtime.md.
 */
internal class SubclassInstances {
    private val instances = HashMap<String, Any>()
    private val supplied = HashMap<String, MutableMap<Int, () -> Any?>>()
    private val creationActions = HashMap<String, MutableList<(Any) -> Unit>>()

    /** The instance of [className] made so far, or null. */
    fun existing(className: String) = instances[className]

    /** Supplies the argument of constructor parameter [index] of [className]; false when the instance exists already. */
    fun supply(className: String, index: Int, value: () -> Any?): Boolean {
        if (className in instances) return false
        supplied.getOrPut(className) { HashMap() }[index] = value
        return true
    }

    /** Runs [action] on the instance of [className] right after its constructor, or at once when it exists. */
    fun onInstanceCreated(className: String, action: (instance: Any) -> Unit) {
        creationActions.getOrPut(className) { mutableListOf() } += action
        instances[className]?.let(action)
    }

    fun instanceOf(target: ClassTarget, context: EnhancementContext) = instances[target.className] ?: create(target, context).also { instances[target.className] = it }

    private fun create(target: ClassTarget, context: EnhancementContext): Any {
        val injected = context.inject(target.className, target.constructorParameters.filter { it.suppliedBy == null }).iterator()
        val arguments = target.constructorParameters.mapIndexed { index, parameter ->
            val processor = parameter.suppliedBy ?: return@mapIndexed injected.next()
            val value = supplied[target.className]?.get(index)
                ?: throw context.failure("${target.className} expects ${processor.substringAfterLast('.')} to supply parameter ${index + 1}, which it has not")
            value()
        }
        return target.instantiator.create(context.classLoader, arguments).also { instance -> creationActions[target.className]?.forEach { it(instance) } }
    }

    companion object {
        private val KEY = StateKey<SubclassInstances>("subclassInstances")

        fun of(context: EnhancementContext) = context.state.getOrPut(KEY) { SubclassInstances() }
    }
}
