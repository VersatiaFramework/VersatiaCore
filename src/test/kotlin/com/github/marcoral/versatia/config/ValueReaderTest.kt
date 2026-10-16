package com.github.marcoral.versatia.config

import com.github.marcoral.versatia.engine.descriptor.TypeReference
import com.github.marcoral.versatia.util.config.ConfigurationValueException
import com.github.marcoral.versatia.util.type.Percent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.bukkit.configuration.file.YamlConfiguration

class Holder {
    enum class Level { LOW, HIGH }
}

class ValueReaderTest {
    private val reader = ValueReader(javaClass.classLoader)

    private fun yaml(content: String) = YamlConfiguration().apply { loadFromString(content) }

    private fun type(name: String, vararg arguments: TypeReference) = TypeReference(name, arguments.toList())

    @Test
    fun `reads exactly the types ValueTypes lists`() {
        assertEquals(ValueTypes.SCALARS + ValueTypes.BUKKIT, ValueReader.SCALARS.keys)
        assertEquals(ValueTypes.SCALARS, ValueReader.LISTS.keys)
    }

    @Test
    fun `reads a value of every scalar type, alone and in a list`() {
        val samples = mapOf(
            "kotlin.Boolean" to ("true" to true),
            "kotlin.Int" to ("3" to 3),
            "kotlin.Long" to ("4" to 4L),
            "kotlin.Float" to ("1.5" to 1.5f),
            "kotlin.Double" to ("2.5" to 2.5),
            "kotlin.UInt" to ("5" to 5u),
            "kotlin.ULong" to ("6" to 6uL),
            "kotlin.String" to ("text" to "text"),
            Percent::class.qualifiedName!! to ("50%" to Percent.of(50.0)),
        )
        assertEquals(ValueTypes.SCALARS, samples.keys)
        for ((name, sample) in samples) {
            val (raw, expected) = sample
            val section = yaml("single: $raw\nlist: [$raw]\n")
            assertEquals(expected, reader.read(section, "single", type(name), null), name)
            assertEquals(listOf(expected), reader.read(section, "list", type(ValueTypes.LIST, type(name)), null), name)
        }
    }

    @Test
    fun `reads a nested enum and rejects malformed values`() {
        val level = type(Holder.Level::class.qualifiedName!!)
        assertEquals(Holder.Level.HIGH, reader.read(yaml("mode: high\n"), "mode", level, null))
        assertEquals(mapOf("a" to Holder.Level.LOW), reader.read(yaml("modes:\n  a: low\n"), "modes", type(ValueTypes.MAP, type("kotlin.String"), level), null))
        assertFailsWith<ConfigurationValueException> { reader.read(yaml("mode: [high]\n"), "mode", level, null) }
        assertFailsWith<ConfigurationValueException> { reader.read(yaml("count: many\n"), "count", type("kotlin.Int"), null) }
    }
}
