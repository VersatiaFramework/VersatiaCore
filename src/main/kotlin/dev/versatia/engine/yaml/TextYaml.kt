package dev.versatia.engine.yaml

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

/**
 * YAML whose every scalar, key or value, is text exactly as written: the YAML 1.1 types Bukkit's
 * loader resolves (`Yes`, `off`, `1.10`, `010`, `12:30`) stay as they are, an empty value is an
 * empty text, and a key written twice in one mapping is an error. What is wrong with a file is
 * said through the caller's `failure`, which makes the exception, under the name the caller gives
 * the file.
 */
internal object TextYaml {
    /** The document in [file]: a map, a list, a text or null for an empty file. */
    fun read(file: File, name: String, failure: (String) -> RuntimeException): Any? = parse(decode(bytes(file, name, failure), name, failure), name, failure)

    fun bytes(file: File, name: String, failure: (String) -> RuntimeException): ByteArray = try {
        file.readBytes()
    } catch (e: IOException) {
        throw failure("$name cannot be read: ${e.message}")
    }

    fun decode(bytes: ByteArray, name: String, failure: (String) -> RuntimeException): String = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw failure("$name is not UTF-8 encoded; save it as UTF-8")
    }

    fun parse(content: String, name: String, failure: (String) -> RuntimeException): Any? = try {
        yaml().load<Any?>(content)
    } catch (e: MarkedYAMLException) {
        val mark = e.problemMark
        throw failure("$name is not valid YAML${if (mark == null) "" else " at line ${mark.line + 1}, column ${mark.column + 1}"}: ${e.problem ?: e.message}")
    } catch (e: YAMLException) {
        throw failure("$name is not valid YAML: ${e.message}")
    }

    private fun yaml(): Yaml {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        return Yaml(SafeConstructor(options), Representer(DumperOptions()), DumperOptions(), options, PlainScalars())
    }

    /** Resolves no implicit type, so every plain scalar is a string. */
    private class PlainScalars : Resolver() {
        override fun addImplicitResolvers() = Unit
    }
}
