package com.github.marcoral.versatia.event

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.subclassInstanceOf
import com.github.marcoral.versatia.failure
import org.bukkit.event.Listener

/**
 * Registers the plugin's `Listener`s with Bukkit once every other processor has run, so the beans
 * exist: a listener that is a bean is registered with its bean instance, any other listener is
 * created like a bean with its constructor parameters injected through the plugin's
 * `BeanResolver`, once per plugin and shared with the other features enhancing its class, and the
 * main class is registered as itself. See docs/runtime.md.
 */
class RegisterListenersEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) = context.onPluginEnhanced {
        for (element in elements) context.plugin.server.pluginManager.registerEvents(listenerOf(element, context), context.plugin)
        context.plugin.logger.fine { "Registered ${elements.size} listeners: ${elements.joinToString { it.target.className }}" }
    }

    private fun listenerOf(element: EnhancedElement, context: EnhancementContext) =
        context.subclassInstanceOf(element, this) as? Listener ?: throw context.failure("${element.target.className} is not a Listener")
}
