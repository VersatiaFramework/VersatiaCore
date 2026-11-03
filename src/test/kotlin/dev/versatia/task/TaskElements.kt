package dev.versatia.task

import dev.versatia.Phase
import dev.versatia.VersatiaPlugin
import dev.versatia.di.BeanProcessors
import dev.versatia.di.LocalBean
import dev.versatia.di.PluginBean
import dev.versatia.engine.descriptor.AnnotationOccurrence
import dev.versatia.engine.descriptor.ClassTarget
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.FunctionTarget
import dev.versatia.engine.descriptor.Instantiator
import dev.versatia.engine.descriptor.Invoker
import dev.versatia.engine.descriptor.MemberOwner
import dev.versatia.engine.descriptor.Parameter
import dev.versatia.engine.descriptor.PluginTarget
import dev.versatia.event.EventProcessors
import dev.versatia.event.RegisterListeners
import dev.versatia.lifecycle.AutoInvoke
import dev.versatia.lifecycle.LifecycleProcessors
import kotlin.reflect.KClass
import org.bukkit.plugin.Plugin

/** Descriptor elements of a plugin extending VersatiaPlugin whose main class is [main], written as the KSP processor writes them. */
class TaskElements(main: String) {
    private val origin = VersatiaPlugin::class.java.name
    private val mainTarget = PluginTarget(main, listOf(main, Plugin::class.qualifiedName!!), emptyList())
    private val runBackgroundTasks = AnnotationOccurrence(RunBackgroundTasks::class.java.name)

    val pluginBean = EnhancedElement(BeanProcessors.PLUGIN, Phase.PLUGIN_RELOAD_OR_START, AnnotationOccurrence(PluginBean::class.java.name), mainTarget, origin)
    val scopeAtStart = EnhancedElement(TaskProcessors.PLUGIN_SCOPE, Phase.PLUGIN_START_ONLY, runBackgroundTasks, mainTarget, origin)
    val scopeAtStop = EnhancedElement(TaskProcessors.PLUGIN_SCOPE, Phase.PLUGIN_STOP, runBackgroundTasks, mainTarget, origin)

    fun task(target: ClassTarget) = EnhancedElement(TaskProcessors.RUN_BACKGROUND_TASKS, Phase.PLUGIN_RELOAD_OR_START, runBackgroundTasks, target, origin)

    fun listener(target: ClassTarget) = EnhancedElement(EventProcessors.REGISTER_LISTENERS, Phase.PLUGIN_START_ONLY, AnnotationOccurrence(RegisterListeners::class.java.name), target, origin)

    fun bean(target: ClassTarget) = EnhancedElement(BeanProcessors.LOCAL, Phase.PLUGIN_START_ONLY, AnnotationOccurrence(LocalBean::class.java.name), target)

    companion object {
        /** A class created through a direct instantiator, taking beans of [parameters]; its assignable types are itself and [supertypes]. */
        inline fun <reified T : Any> classTarget(parameters: List<KClass<*>> = emptyList(), supertypes: List<KClass<*>> = emptyList(), noinline create: (List<Any?>) -> T) = ClassTarget(
            T::class.qualifiedName!!,
            (listOf(T::class) + supertypes).map { it.qualifiedName!! },
            emptyList(),
            parameters.map { Parameter(it.qualifiedName!!) },
            Instantiator.Direct(create),
        )

        /** An `@AutoInvoke` function of the object [T]. */
        inline fun <reified T : Any> function(name: String, phase: Phase, noinline call: () -> Unit) = EnhancedElement(
            LifecycleProcessors.AUTO_INVOKE,
            phase,
            AnnotationOccurrence(AutoInvoke::class.java.name, mapOf("phase" to phase.name)),
            FunctionTarget(T::class.qualifiedName!!, T::class.java.name, name, MemberOwner.OBJECT, emptyList(), emptyList(), Invoker.Direct { _, _ -> call() }),
        )
    }
}
