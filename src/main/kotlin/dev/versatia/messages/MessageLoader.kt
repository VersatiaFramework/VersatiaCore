package dev.versatia.messages

import dev.versatia.text.Text
import dev.versatia.util.config.YAML_EXTENSIONS
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.logging.Logger
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

/**
 * Reads every `.yml`/`.yaml` under `<data folder>/messages`, recursively, ordered by the path
 * relative to the data folder with `/` separators. Sections nest into dotted keys and a list is a
 * message of several lines, kept apart. Every scalar, key or value, is text exactly as written: the YAML 1.1
 * types Bukkit's loader resolves (`Yes`, `off`, `1.10`, `010`, `12:30`) stay as they are. Files are
 * containers only: the file name is not part of the key, and a key defined twice, in one file or in
 * two, is an error, and so is a key that is a message in one place and a group of messages in another.
 * A file whose name starts with a dot holds no messages; [SETTINGS] holds the settings. Once every
 * file is read, a message with legacy colour codes is reported to [logger], unless the settings
 * turn that off.
 */
internal class MessageLoader(private val pluginName: String, private val dataFolder: File, private val text: Text, private val logger: Logger) {
    fun load(): Map<String, Message> {
        val messages = LinkedHashMap<String, Message>()
        val groups = HashMap<String, String>()
        val legacyWarnings = legacyWarnings()
        for ((file, relative) in files()) {
            collect(read(file, relative), "", relative).forEach { (key, lines) ->
                messages[key]?.let { throw failure(if (it.file == relative) "message \"$key\" is defined twice in $relative" else "message \"$key\" is defined in both ${it.file} and $relative") }
                groups[key]?.let { throw collision(key, relative, it) }
                for (group in groupsOf(key)) {
                    messages[group]?.let { throw collision(group, it.file, relative) }
                    groups.putIfAbsent(group, relative)
                }
                messages[key] = MessageImpl(pluginName, key, lines, relative, text)
            }
        }
        if (legacyWarnings) messages.values.forEach(::warnLegacy)
        return messages
    }

    private fun warnLegacy(message: Message) {
        val codes = LegacyColorCodes.find(message.raw).ifEmpty { return }
        logger.warning(
            "Message \"${message.key}\" in ${message.file} uses legacy colour codes, which MiniMessage shows as text: " +
                codes.joinToString { "$it -> ${LegacyColorCodes.tag(it)}" } +
                "; write the tags instead, or turn this warning off with \"$LEGACY_WARNINGS: false\" in $DIRECTORY/$SETTINGS",
        )
    }

    private fun legacyWarnings(): Boolean {
        val relative = "$DIRECTORY/$SETTINGS"
        val file = dataFolder.resolve(relative).takeIf { it.isFile } ?: return true
        var enabled = true
        for ((name, value) in read(file, relative)) {
            if (name != LEGACY_WARNINGS) throw failure("$relative has an unknown setting \"$name\"; the settings are: $LEGACY_WARNINGS")
            enabled = when (value) {
                "true" -> true
                "false" -> false
                else -> throw failure("setting \"$name\" in $relative must be true or false, not \"$value\"")
            }
        }
        return enabled
    }

    /** `a.b.c` lies in the groups `a` and `a.b`. */
    private fun groupsOf(key: String) = key.indices.filter { key[it] == '.' }.map { key.substring(0, it) }

    private fun collision(key: String, message: String, group: String) = failure(
        if (message == group) "\"$key\" is both a message and a group of messages in $message; rename one of them"
        else "\"$key\" is a message in $message and a group of messages in $group; rename one of them",
    )

    private fun files() = dataFolder.resolve(DIRECTORY).takeIf { it.isDirectory }?.walk().orEmpty()
        .filter { it.isFile && !it.name.startsWith('.') && it.extension.lowercase() in YAML_EXTENSIONS }
        .map { it to it.relativeTo(dataFolder).invariantSeparatorsPath }
        .sortedBy { it.second }
        .toList()

    private fun read(file: File, relative: String): Map<*, *> {
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            throw failure("$relative cannot be read: ${e.message}")
        }
        val content = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw failure("$relative is not UTF-8 encoded; save it as UTF-8")
        }
        val document = try {
            yaml().load<Any?>(content)
        } catch (e: MarkedYAMLException) {
            val mark = e.problemMark
            throw failure("$relative is not valid YAML${if (mark == null) "" else " at line ${mark.line + 1}, column ${mark.column + 1}"}: ${e.problem ?: e.message}")
        } catch (e: YAMLException) {
            throw failure("$relative is not valid YAML: ${e.message}")
        }
        return when (document) {
            null -> emptyMap<Any, Any>()
            is Map<*, *> -> document
            else -> throw failure("$relative must map keys to messages")
        }
    }

    private fun collect(section: Map<*, *>, prefix: String, file: String): List<Pair<String, List<String>>> = section.flatMap { (name, value) ->
        if (name !is String) throw failure("$file has a key that is not text: $name")
        val key = "$prefix$name"
        when (value) {
            is String -> listOf(key to listOf(value))
            is Map<*, *> -> collect(value, "$key.", file)
            is List<*> -> listOf(key to value.map { it as? String ?: throw failure("message \"$key\" in $file is a list whose lines must all be text") })
            else -> throw failure("message \"$key\" in $file is not text")
        }
    }

    private fun failure(message: String) = MessagesLoadException(pluginName, message)

    /** Resolves no implicit type, so every plain scalar is a string. */
    private class PlainScalars : Resolver() {
        override fun addImplicitResolvers() = Unit
    }

    companion object {
        const val DIRECTORY = "messages"
        const val SETTINGS = ".versatia.yml"
        const val LEGACY_WARNINGS = "legacy-color-warnings"

        private fun yaml(): Yaml {
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
            return Yaml(SafeConstructor(options), Representer(DumperOptions()), DumperOptions(), options, PlainScalars())
        }
    }
}
