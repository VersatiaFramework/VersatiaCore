package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.StateKey
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.inject
import com.github.marcoral.versatia.targetAs

private val SUBCLASS_INSTANCES = StateKey<MutableMap<String, Any>>("subclassInstances")

/**
 * The instance a subclass-enhancement element stands for: the plugin for its PluginTarget, the
 * bean of the class when the plugin has one, otherwise one instance per plugin created through
 * the element's instantiator with its constructor parameters injected and shared by every feature
 * enhancing the class, so a plain class that is a Listener and a BackgroundTask exists once.
 */
internal fun EnhancementContext.subclassInstanceOf(element: EnhancedElement, processor: EnhancementProcessor): Any = when (val target = element.target) {
    is PluginTarget -> plugin
    is ClassTarget -> state[BeanResolver.KEY]?.instanceOf(target.className)
        ?: state.getOrPut(SUBCLASS_INSTANCES) { HashMap() }.getOrPut(target.className) {
            target.instantiator.create(classLoader, inject(target.className, target.constructorParameters))
        }
    is MemberTarget -> element.targetAs<ClassTarget>(processor)
}
