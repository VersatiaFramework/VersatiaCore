package com.github.marcoral.versatia

import com.github.marcoral.versatia.util.reflect.forceAccessible
import com.github.marcoral.versatia.util.reflect.objectInstanceOrNull
import java.lang.reflect.InvocationTargetException

/**
 * Loads enhancement processors by the class name a descriptor gives, through the enhanced plugin's
 * class loader, so a processor may live in VersatiaCore or in any plugin the enhanced one depends
 * on. One instance per class, kept for the lifetime of the runtime. See docs/runtime.md.
 */
class ProcessorLoader {
    private val instances = HashMap<Class<*>, EnhancementProcessor>()

    fun load(className: String, context: EnhancementContext): EnhancementProcessor {
        val clazz = try {
            Class.forName(className, true, context.classLoader)
        } catch (e: ClassNotFoundException) {
            throw EnhancementException(
                "Plugin ${context.plugin.name} needs the enhancement processor $className, which is not available. " +
                    "Is the plugin providing it installed and listed under depend:?",
                e,
            )
        }
        return instances.getOrPut(clazz) { instantiate(clazz) }
    }

    private fun instantiate(clazz: Class<*>): EnhancementProcessor {
        val instance = clazz.objectInstanceOrNull() ?: newInstance(clazz)
        return instance as? EnhancementProcessor
            ?: throw EnhancementException("${clazz.name} does not implement ${EnhancementProcessor::class.java.name}")
    }

    /** A missing constructor is told apart from one that throws, whose own exception is the reported cause. */
    private fun newInstance(clazz: Class<*>): Any {
        val constructor = try {
            clazz.getDeclaredConstructor().forceAccessible()
        } catch (e: NoSuchMethodException) {
            throw EnhancementException("Enhancement processor ${clazz.name} has no no-argument constructor", e)
        }
        return try {
            constructor.newInstance()
        } catch (e: InvocationTargetException) {
            throw EnhancementException("Enhancement processor ${clazz.name} failed to initialise", e.targetException)
        } catch (e: ReflectiveOperationException) {
            throw EnhancementException("Enhancement processor ${clazz.name} could not be created", e)
        }
    }
}
