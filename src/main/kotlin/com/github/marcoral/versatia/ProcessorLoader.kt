package com.github.marcoral.versatia

import com.github.marcoral.versatia.util.reflect.forceAccessible
import com.github.marcoral.versatia.util.reflect.objectInstanceOrNull
import java.lang.reflect.InvocationTargetException

/**
 * Loads enhancement processors by the class name a descriptor gives, through the enhanced plugin's
 * class loader, so a processor may live in VersatiaCore or in any plugin the enhanced one depends
 * on. One instance per class, kept with the class itself, so a processor of a plugin that is gone
 * does not keep that plugin's class loader alive. The class is checked to be a processor before
 * anything of it runs. See docs/runtime.md.
 */
class ProcessorLoader {
    private val instances = object : ClassValue<EnhancementProcessor>() {
        override fun computeValue(type: Class<*>) = instantiate(type)
    }

    fun load(className: String, context: EnhancementContext): EnhancementProcessor {
        val clazz = try {
            Class.forName(className, false, context.classLoader)
        } catch (e: ClassNotFoundException) {
            throw EnhancementException(
                "Plugin ${context.plugin.name} needs the enhancement processor $className, which is not available. " +
                    "Is the plugin providing it installed and listed under depend:?",
                e,
            )
        } catch (e: LinkageError) {
            throw EnhancementException("Plugin ${context.plugin.name}: the enhancement processor $className cannot be loaded ($e); is the plugin providing it built for this VersatiaCore?", e)
        }
        if (!EnhancementProcessor::class.java.isAssignableFrom(clazz))
            throw EnhancementException("Plugin ${context.plugin.name}: ${clazz.name} does not implement ${EnhancementProcessor::class.java.name}")
        return try {
            instances.get(clazz)
        } catch (e: LinkageError) {
            throw EnhancementException("Plugin ${context.plugin.name}: enhancement processor ${clazz.name} failed to initialise ($e)", e)
        }
    }

    private fun instantiate(clazz: Class<*>) = (clazz.objectInstanceOrNull() ?: newInstance(clazz)) as EnhancementProcessor

    /** A missing constructor is told apart from one that throws, whose own exception is the reported cause. */
    private fun newInstance(clazz: Class<*>): Any {
        val constructor = try {
            clazz.getDeclaredConstructor().forceAccessible()
        } catch (e: NoSuchMethodException) {
            throw EnhancementException("Enhancement processor ${clazz.name} has no no-argument constructor", e)
        } catch (e: RuntimeException) {
            throw EnhancementException("Enhancement processor ${clazz.name} cannot be made accessible", e)
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
