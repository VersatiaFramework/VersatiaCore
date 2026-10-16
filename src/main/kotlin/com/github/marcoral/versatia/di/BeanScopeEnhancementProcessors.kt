package com.github.marcoral.versatia.di

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.LifecycleEvent
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.targetAs

/**
 * Shared by the three scope processors: every element becomes a bean definition in the plugin's
 * session, which creates them once all processors have run.
 */
abstract class BeanScopeEnhancementProcessor(private val scope: BeanScope) : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val definitions = elements.map { BeanDefinition.of(scope, it.targetAs<ClassTarget>(this)) }
        PluginBeanSession.of(context).declare(context, definitions)
    }
}

class LocalBeanEnhancementProcessor : BeanScopeEnhancementProcessor(BeanScope.LOCAL)

class ExposeSingletonEnhancementProcessor : BeanScopeEnhancementProcessor(BeanScope.EXPOSED_SINGLETON)

class ExposePrototypeEnhancementProcessor : BeanScopeEnhancementProcessor(BeanScope.EXPOSED_PROTOTYPE)

/**
 * At start, registers the plugin's main class, whose instance already exists, as the local bean
 * named `plugin`; at a reload, reloads the session's `Reloadable` beans once every processor has
 * run, so after the `@Value` properties are set, and before the auto-invoked functions.
 */
class PluginBeanEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val target = elements.singleOrNull()?.target as? PluginTarget
            ?: throw EnhancementException("Plugin ${context.plugin.name} must declare its main class exactly once, got ${elements.map { it.target }}")
        val session = PluginBeanSession.of(context)
        when (context.event) {
            LifecycleEvent.RELOAD -> context.onPluginEnhanced { session.reload() }
            else -> session.declare(context, listOf(BeanDefinition.plugin(target, context.plugin)))
        }
    }
}
