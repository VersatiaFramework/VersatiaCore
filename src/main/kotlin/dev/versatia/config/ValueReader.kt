package dev.versatia.config

import dev.versatia.EnhancementException
import dev.versatia.engine.descriptor.TypeReference
import dev.versatia.util.config.ConfigurationValueException
import dev.versatia.util.config.getBooleanListOrThrow
import dev.versatia.util.config.getBooleanOrThrow
import dev.versatia.util.config.getColorOrThrow
import dev.versatia.util.config.getConfigurationSectionOrThrow
import dev.versatia.util.config.getDoubleListOrThrow
import dev.versatia.util.config.getDoubleOrThrow
import dev.versatia.util.config.getEnumElementList
import dev.versatia.util.config.getEnumElementOrThrow
import dev.versatia.util.config.getFloatListOrThrow
import dev.versatia.util.config.getFloatOrThrow
import dev.versatia.util.config.getIntListOrThrow
import dev.versatia.util.config.getIntOrThrow
import dev.versatia.util.config.getItemStackOrThrow
import dev.versatia.util.config.getLocationOrThrow
import dev.versatia.util.config.getLongListOrThrow
import dev.versatia.util.config.getLongOrThrow
import dev.versatia.util.config.getOfflinePlayerOrThrow
import dev.versatia.util.config.getDurationList
import dev.versatia.util.config.getDurationOrThrow
import dev.versatia.util.config.getPercentList
import dev.versatia.util.config.getPercentOrThrow
import dev.versatia.util.config.getSecretList
import dev.versatia.util.config.getSecretOrThrow
import dev.versatia.util.config.getStringListOrThrow
import dev.versatia.util.config.getStringOrThrow
import dev.versatia.util.config.getUIntList
import dev.versatia.util.config.getUIntOrThrow
import dev.versatia.util.config.getULongList
import dev.versatia.util.config.getULongOrThrow
import dev.versatia.util.config.getVectorOrThrow
import dev.versatia.util.config.pathOf
import dev.versatia.util.reflect.objectOrNewInstance
import dev.versatia.util.type.Percent
import dev.versatia.util.type.Secret
import kotlin.time.Duration
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
    private val mappers = HashMap<String, ValueMapper<*>>()

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

    /** The mapper's `object` instance, or a new instance from its no-argument constructor, made once per reader. */
    private fun mapperInstance(name: String) = mappers.getOrPut(name) {
        val clazz = loadClass(name)
        if (!ValueMapper::class.java.isAssignableFrom(clazz)) throw EnhancementException("$name does not implement ${ValueMapper::class.java.name}")
        clazz.objectOrNewInstance() as ValueMapper<*>
    }

    /** Loads a class by its Kotlin qualified name, trying the binary names a nested class may have. */
    private fun loadClass(qualifiedName: String): Class<*> {
        var name = qualifiedName
        while (true) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (e: ClassNotFoundException) {
                val dot = name.lastIndexOf('.')
                if (dot < 0) throw EnhancementException("$qualifiedName cannot be loaded: ${e.message}", e)
                name = name.substring(0, dot) + "$" + name.substring(dot + 1)
            }
        }
    }

    internal companion object {
        private val TRACKING = PreviousValueTracking::class.qualifiedName!!

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
            Duration::class.qualifiedName!! to { getDurationOrThrow(it) },
            Secret::class.qualifiedName!! to { getSecretOrThrow(it) },
            Color::class.qualifiedName!! to { getColorOrThrow(it) },
            Location::class.qualifiedName!! to { getLocationOrThrow(it) },
            OfflinePlayer::class.qualifiedName!! to { getOfflinePlayerOrThrow(it) },
            Vector::class.qualifiedName!! to { getVectorOrThrow(it) },
            ItemStack::class.qualifiedName!! to { getItemStackOrThrow(it) },
            ConfigurationSection::class.qualifiedName!! to { getConfigurationSectionOrThrow(it) },
        )

        val LISTS: Map<String, ConfigurationSection.(String) -> List<Any>> = mapOf(
            Boolean::class.qualifiedName!! to { getBooleanListOrThrow(it) },
            Int::class.qualifiedName!! to { getIntListOrThrow(it) },
            Long::class.qualifiedName!! to { getLongListOrThrow(it) },
            Float::class.qualifiedName!! to { getFloatListOrThrow(it) },
            Double::class.qualifiedName!! to { getDoubleListOrThrow(it) },
            UInt::class.qualifiedName!! to { getUIntList(it) },
            ULong::class.qualifiedName!! to { getULongList(it) },
            String::class.qualifiedName!! to { getStringListOrThrow(it) },
            Percent::class.qualifiedName!! to { getPercentList(it) },
            Duration::class.qualifiedName!! to { getDurationList(it) },
            Secret::class.qualifiedName!! to { getSecretList(it) },
        )
    }
}
