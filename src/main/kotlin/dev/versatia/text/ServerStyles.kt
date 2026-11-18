package dev.versatia.text

import dev.versatia.di.LocalBean
import dev.versatia.di.Reloadable
import dev.versatia.engine.yaml.TextYaml
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.bukkit.plugin.Plugin

/**
 * The styles of the server, read from [FILE] in VersatiaCore's data folder when VersatiaCore starts
 * and at each of its reloads; a file that cannot be read keeps the styles in use. The `Text` of
 * every plugin reads [sheet], so a style looks the same in all of them, and [adopt]s the plugin's
 * defaults: the styles it ships in [FILE] next to its `plugin.yml` and the server file lacks are
 * appended to the server file, which is the one place an admin edits. VersatiaCore's own defaults,
 * the styles every plugin shares, are adopted first, so they are there before any plugin starts.
 */
@LocalBean
internal class ServerStyles(private val plugin: Plugin) : Reloadable {
    private val file = plugin.dataFolder.resolve(FILE)

    /** The file as messages name it: `plugins/VersatiaCore/styles.yml`. */
    private val source = listOfNotNull(plugin.dataFolder.absoluteFile.parentFile?.name, plugin.dataFolder.name, FILE).joinToString("/")

    @Volatile
    var sheet = StyleSheet.none(source)
        private set

    override fun reload() {
        sheet = StyleFile(source).read(content())
        adopt(plugin)
    }

    /**
     * Appends the default styles of [plugin] that the server file lacks, under a comment naming
     * the plugin; the file wins wherever it defines a style. The file is read again first, so what
     * an admin changed since the last reload is neither lost nor defined twice. A plugin without
     * defaults, or whose defaults are all there, costs no file access.
     */
    fun adopt(plugin: Plugin) {
        val shipped = "$FILE in the JAR of ${plugin.name}"
        val bytes = plugin.getResource(FILE)?.use { it.readBytes() } ?: return
        val defaults = StyleFile(shipped).read(TextYaml.decode(bytes, shipped, ::StylesLoadException))
        if (sheet.lacking(defaults).isEmpty()) return
        val content = content()
        val format = StyleFile(source)
        val current = format.read(content)
        val additions = format.additions(current, defaults, plugin.name)
        if (additions.isEmpty()) {
            sheet = current
            return
        }
        val lineBreak = if ("\r\n" in content) "\r\n" else "\n"
        val gap = if (content.isEmpty() || content.endsWith("\n\n") || content.endsWith("\r\n\r\n")) "" else if (content.endsWith("\n")) lineBreak else lineBreak + lineBreak
        val appended = content + gap + additions.replace("\n", lineBreak)
        val next = format.read(appended)
        write(appended)
        sheet = next
        plugin.logger.info("Added the default styles ${current.lacking(defaults).joinToString()} to $source")
    }

    private fun content() = if (file.isFile) TextYaml.decode(TextYaml.bytes(file, source, ::StylesLoadException), source, ::StylesLoadException) else ""

    /** Replaces the file in one step, keeping its permissions where the file system has them; a link an admin put in its place is written through. */
    private fun write(content: String) {
        try {
            val path = file.toPath().let { if (Files.isSymbolicLink(it)) it.toRealPath() else it.toAbsolutePath() }
            Files.createDirectories(path.parent)
            val temporary = Files.createTempFile(path.parent, ".${path.fileName}", ".tmp")
            try {
                Files.writeString(temporary, content)
                if (Files.exists(path)) runCatching { Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path)) }
                try {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (e: IOException) {
            throw StylesLoadException("$source cannot be written: ${e.message}")
        }
    }

    companion object {
        const val FILE = "styles.yml"
    }
}
