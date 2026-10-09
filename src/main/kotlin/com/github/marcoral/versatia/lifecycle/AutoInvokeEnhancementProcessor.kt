package com.github.marcoral.versatia.lifecycle

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionOwner
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget

/**
 * Calls `@AutoInvoke` functions once every other processor has run for the event, in descriptor
 * order, injecting their parameters through the plugin's `BeanResolver`; see docs/runtime.md.
 */
class AutoInvokeEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val targets = elements.map {
            it.target as? FunctionTarget ?: throw EnhancementException("${javaClass.name} can only enhance functions, got ${it.target}")
        }
        context.onPluginEnhanced { targets.forEach { invoke(it, context) } }
    }

    private fun invoke(target: FunctionTarget, context: EnhancementContext) {
        val description = "${target.className}.${target.functionName}()"
        val beans = context.state[BeanResolver.KEY]
        val instance = when (target.owner) {
            FunctionOwner.PLUGIN -> context.plugin
            FunctionOwner.OBJECT -> objectInstance(target, context)
            FunctionOwner.INSTANCE -> beans?.instanceOf(target.className)
                ?: throw EnhancementException("Plugin ${context.plugin.name}: no bean ${target.className} exists to call $description on")
        }
        val arguments = when {
            target.parameters.isEmpty() -> emptyList()
            beans == null -> throw EnhancementException("Plugin ${context.plugin.name}: $description needs injected parameters, but the plugin has no beans")
            else -> beans.resolve(description, target.parameters)
        }
        try {
            target.invoker.invoke(context.classLoader, instance, arguments)
        } catch (e: EnhancementException) {
            throw e
        } catch (e: Exception) {
            throw EnhancementException("Plugin ${context.plugin.name}: $description failed at ${context.event}", e)
        }
    }

    private fun objectInstance(target: FunctionTarget, context: EnhancementContext) =
        Class.forName(target.binaryClassName, true, context.classLoader).getDeclaredField("INSTANCE").get(null)
}
