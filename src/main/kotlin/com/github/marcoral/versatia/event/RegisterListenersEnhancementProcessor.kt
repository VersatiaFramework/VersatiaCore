package com.github.marcoral.versatia.event

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.ElementTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import org.bukkit.event.Listener

/**
 * Registers the plugin's `Listener`s with Bukkit once every other processor has run, so the beans
 * exist: a listener that is a bean is registered with its bean instance, any other listener is
 * created like a bean with its constructor parameters injected through the plugin's
 * `BeanResolver`, and the main class is registered as itself. See docs/runtime.md.
 */
class RegisterListenersEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = context.onPluginEnhanced {
        val beans = context.state[BeanResolver.KEY]
        for (element in elements) context.plugin.server.pluginManager.registerEvents(listenerOf(element.target, context, beans), context.plugin)
        context.plugin.logger.fine { "Registered ${elements.size} listeners: ${elements.joinToString { it.target.className }}" }
    }

    private fun listenerOf(target: ElementTarget, context: EnhancementContext, beans: BeanResolver?): Listener {
        val instance = when (target) {
            is PluginTarget -> context.plugin
            is ClassTarget -> beans?.instanceOf(target.className) ?: create(target, context, beans)
            is FunctionTarget -> throw EnhancementException("${javaClass.name} can only enhance classes, got $target")
        }
        return instance as? Listener ?: throw EnhancementException("Plugin ${context.plugin.name}: ${target.className} is not a Listener")
    }

    private fun create(target: ClassTarget, context: EnhancementContext, beans: BeanResolver?): Any {
        val arguments = when {
            target.constructorParameters.isEmpty() -> emptyList()
            beans == null -> throw EnhancementException("Plugin ${context.plugin.name}: ${target.className} needs injected constructor parameters, but the plugin has no beans")
            else -> beans.resolve(target.className, target.constructorParameters)
        }
        return target.instantiator.create(context.classLoader, arguments)
    }
}
