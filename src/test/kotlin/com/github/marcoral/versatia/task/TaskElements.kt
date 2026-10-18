package com.github.marcoral.versatia.task

import com.github.marcoral.versatia.Phase
import com.github.marcoral.versatia.VersatiaPlugin
import com.github.marcoral.versatia.di.BeanProcessors
import com.github.marcoral.versatia.di.LocalBean
import com.github.marcoral.versatia.di.PluginBean
import com.github.marcoral.versatia.engine.descriptor.AnnotationOccurrence
import com.github.marcoral.versatia.engine.descriptor.ClassTarget
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.FunctionTarget
import com.github.marcoral.versatia.engine.descriptor.Instantiator
import com.github.marcoral.versatia.engine.descriptor.Invoker
import com.github.marcoral.versatia.engine.descriptor.MemberOwner
import com.github.marcoral.versatia.engine.descriptor.Parameter
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import com.github.marcoral.versatia.event.EventProcessors
import com.github.marcoral.versatia.event.RegisterListeners
import com.github.marcoral.versatia.lifecycle.AutoInvoke
import com.github.marcoral.versatia.lifecycle.LifecycleProcessors
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
