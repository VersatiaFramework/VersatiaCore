package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.engine.descriptor.TypeReference
import com.github.marcoral.versatia.util.config.ConfigurationValueException
import com.github.marcoral.versatia.util.config.getBooleanOrThrow
import com.github.marcoral.versatia.util.config.getColorOrThrow
import com.github.marcoral.versatia.util.config.getConfigurationSectionOrThrow
import com.github.marcoral.versatia.util.config.getDoubleOrThrow
import com.github.marcoral.versatia.util.config.getEnumElementList
import com.github.marcoral.versatia.util.config.getEnumElementOrThrow
import com.github.marcoral.versatia.util.config.getFloatOrThrow
import com.github.marcoral.versatia.util.config.getIntOrThrow
import com.github.marcoral.versatia.util.config.getItemStackOrThrow
import com.github.marcoral.versatia.util.config.getLocationOrThrow
import com.github.marcoral.versatia.util.config.getLongOrThrow
import com.github.marcoral.versatia.util.config.getOfflinePlayerOrThrow
import com.github.marcoral.versatia.util.config.getPercentList
import com.github.marcoral.versatia.util.config.getPercentOrThrow
import com.github.marcoral.versatia.util.config.getStringOrThrow
import com.github.marcoral.versatia.util.config.getUIntList
import com.github.marcoral.versatia.util.config.getUIntOrThrow
import com.github.marcoral.versatia.util.config.getULongList
import com.github.marcoral.versatia.util.config.getULongOrThrow
import com.github.marcoral.versatia.util.config.getVectorOrThrow
import com.github.marcoral.versatia.util.config.pathOf
import com.github.marcoral.versatia.util.reflect.forceGetObjectInstance
import com.github.marcoral.versatia.util.type.Percent
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

/**
 * Reads the value a `@Value` property's type asks for with the strict getters of `util.config`:
 * a scalar or Bukkit type as one value, an enum by constant name, a `List`, `Set` or `Map` of
 * scalars or enums element by element; with a mapper named, the mapper reads instead, and a
 * `PreviousValueTracking` is read by the type it holds. Enum and
 * mapper classes are loaded through [classLoader], the enhanced plugin's. See docs/runtime.md.
 */
internal class ValueReader(private val classLoader: ClassLoader) {
    fun read(section: ConfigurationSection, key: String, type: TypeReference, mapper: String?): Any? {
        if (section.get(key) == null) {
            if (type.nullable || type.name == TRACKING && type.arguments.single().nullable) return null
            throw ConfigurationValueException("No value present at \"${section.pathOf(key)}\"")
        }
        if (type.name == TRACKING) return read(section, key, type.arguments.single(), mapper)
        if (mapper != null) return mapperInstance(mapper).map(section, key)
        return when (type.name) {
            ValueTypes.LIST -> list(section, key, type.arguments.single())
            ValueTypes.SET -> list(section, key, type.arguments.single()).toSet()
            ValueTypes.MAP -> section.getConfigurationSectionOrThrow(key).let { nested ->
                nested.getKeys(false).associateWith { scalar(type.arguments[1])(nested, it) }
            }
            else -> scalar(type)(section, key)
        }
    }

    private fun scalar(type: TypeReference): ConfigurationSection.(String) -> Any =
        SCALARS[type.name] ?: enumClass(type).let { enum -> { getEnumElementOrThrow(it, enum) } }

    private fun list(section: ConfigurationSection, key: String, element: TypeReference): List<Any> =
        LISTS[element.name]?.invoke(section, key) ?: section.getEnumElementList(key, enumClass(element))

    @Suppress("UNCHECKED_CAST")
    private fun enumClass(type: TypeReference): Class<out Enum<*>> {
        val clazz = loadClass(type.name)
        if (!clazz.isEnum) throw ConfigurationValueException("${type.name} is neither a supported type nor an enum")
        return clazz as Class<out Enum<*>>
    }

    /** The mapper's `object` instance, or a new instance from its no-argument constructor. */
    private fun mapperInstance(name: String): ValueMapper<*> {
        val clazz = loadClass(name)
        val instance = clazz.kotlin.forceGetObjectInstance() ?: clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        return instance as? ValueMapper<*> ?: throw EnhancementException("$name does not implement ${ValueMapper::class.java.name}")
    }

    /** Loads a class by its Kotlin qualified name, trying the binary names a nested class may have. */
    private fun loadClass(qualifiedName: String): Class<*> {
        var name = qualifiedName
        while (true) {
            try {
                return Class.forName(name, true, classLoader)
            } catch (e: ClassNotFoundException) {
                val dot = name.lastIndexOf('.')
                if (dot < 0) throw EnhancementException("$qualifiedName cannot be loaded: ${e.message}", e)
                name = name.substring(0, dot) + "$" + name.substring(dot + 1)
            }
        }
    }

    private companion object {
        val TRACKING = PreviousValueTracking::class.qualifiedName!!

        val SCALARS: Map<String, ConfigurationSection.(String) -> Any> = mapOf(
            Boolean::class.qualifiedName!! to { getBooleanOrThrow(it) },
            Int::class.qualifiedName!! to { getIntOrThrow(it) },
            Long::class.qualifiedName!! to { getLongOrThrow(it) },
            Float::class.qualifiedName!! to { getFloatOrThrow(it) },
            Double::class.qualifiedName!! to { getDoubleOrThrow(it) },
            UInt::class.qualifiedName!! to { getUIntOrThrow(it) },
            ULong::class.qualifiedName!! to { getULongOrThrow(it) },
            String::class.qualifiedName!! to { getStringOrThrow(it) },
            Percent::class.qualifiedName!! to { getPercentOrThrow(it) },
            Color::class.qualifiedName!! to { getColorOrThrow(it) },
            Location::class.qualifiedName!! to { getLocationOrThrow(it) },
            OfflinePlayer::class.qualifiedName!! to { getOfflinePlayerOrThrow(it) },
            Vector::class.qualifiedName!! to { getVectorOrThrow(it) },
            ItemStack::class.qualifiedName!! to { getItemStackOrThrow(it) },
            ConfigurationSection::class.qualifiedName!! to { getConfigurationSectionOrThrow(it) },
        )

        val LISTS: Map<String, ConfigurationSection.(String) -> List<Any>> = mapOf(
            Boolean::class.qualifiedName!! to { getBooleanList(it) },
            Int::class.qualifiedName!! to { getIntegerList(it) },
            Long::class.qualifiedName!! to { getLongList(it) },
            Float::class.qualifiedName!! to { getFloatList(it) },
            Double::class.qualifiedName!! to { getDoubleList(it) },
            UInt::class.qualifiedName!! to { getUIntList(it) },
            ULong::class.qualifiedName!! to { getULongList(it) },
            String::class.qualifiedName!! to { getStringList(it) },
            Percent::class.qualifiedName!! to { getPercentList(it) },
        )
    }
}
