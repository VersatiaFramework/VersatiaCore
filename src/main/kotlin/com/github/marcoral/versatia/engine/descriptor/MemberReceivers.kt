package com.github.marcoral.versatia.engine.descriptor

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.di.BeanResolver

/**
 * The instance a member of [className] belongs to, by its [owner]: the plugin being enhanced, the
 * instance of a Kotlin `object`, or the bean of the class from the plugin's `BeanResolver`;
 * [purpose] completes the error for a missing bean, such as `call X.f() on`.
 */
internal fun EnhancementContext.receiverOf(owner: MemberOwner, className: String, binaryClassName: String, purpose: String): Any = when (owner) {
    MemberOwner.PLUGIN -> plugin
    MemberOwner.OBJECT -> Class.forName(binaryClassName, true, classLoader).getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    MemberOwner.INSTANCE -> state[BeanResolver.KEY]?.instanceOf(className)
        ?: throw EnhancementException("Plugin ${plugin.name}: no bean $className exists to $purpose")
}
