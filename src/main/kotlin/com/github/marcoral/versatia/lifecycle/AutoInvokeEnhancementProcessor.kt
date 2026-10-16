package com.github.marcoral.versatia.lifecycle

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.di.BeanResolver
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.receiverOf
import com.github.marcoral.versatia.runSteps

/**
 * Calls `@AutoInvoke` functions once every other processor has run for the event, in descriptor
 * order, injecting their parameters through the plugin's `BeanResolver`. At stop every function
 * runs even when another fails. See docs/runtime.md.
 */
class AutoInvokeEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val targets = elements.map {
            it.target as? FunctionTarget ?: throw EnhancementException("${javaClass.name} can only enhance functions, got ${it.target}")
        }
        context.onPluginEnhanced { context.runSteps(targets.map { target -> { invoke(target, context) } }) }
    }

    private fun invoke(target: FunctionTarget, context: EnhancementContext) {
        val description = "${target.className}.${target.functionName}()"
        val instance = context.receiverOf(target.owner, target.className, target.binaryClassName, "call $description on")
        val beans = context.state[BeanResolver.KEY]
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
            throw failure(description, context, e)
        } catch (e: LinkageError) {
            throw failure(description, context, e)
        }
    }

    private fun failure(description: String, context: EnhancementContext, cause: Throwable) =
        EnhancementException("Plugin ${context.plugin.name}: $description failed at ${context.event}", cause)
}
