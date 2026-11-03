package dev.versatia.resources

import dev.versatia.EnhancementContext
import dev.versatia.EnhancementException
import dev.versatia.EnhancementProcessor
import dev.versatia.classLoader
import dev.versatia.engine.descriptor.EnhancedElement
import dev.versatia.engine.descriptor.PluginTarget
import java.io.File
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.jar.JarFile

/**
 * Copies the files under the `resources` directory of the plugin's code source (its JAR on a
 * server, a class directory in tests) into the plugin's data folder, never replacing a file that
 * is already there. Runs right away, so the files exist before any bean is created or any function
 * is auto-invoked. See docs/runtime.md.
 */
class ExtractResourcesEnhancementProcessor : EnhancementProcessor {
    override fun process(elements: List<EnhancedElement>, context: EnhancementContext) {
        val target = elements.singleOrNull()?.target as? PluginTarget
            ?: throw EnhancementException("${javaClass.name} expects the plugin's main class exactly once, got ${elements.map { it.target }}")
        val extracted = mutableListOf<String>()
        var found = 0
        forEachResource(codeSourceOf(target.className, context), context) { path, content ->
            found++
            if (extract(path, content, context)) extracted += path
        }
        if (found > 0) context.plugin.logger.fine { "Extracted ${extracted.size} of $found resources: ${extracted.joinToString()}" }
    }

    private fun codeSourceOf(className: String, context: EnhancementContext): File {
        val location = Class.forName(className, false, context.classLoader).protectionDomain.codeSource?.location
            ?: throw EnhancementException("Plugin ${context.plugin.name}: the code of $className has no location, so its resources cannot be extracted")
        return File(location.toURI())
    }

    /** Calls [action] for every file under the resources directory of [source], with its path relative to that directory. */
    private fun forEachResource(source: File, context: EnhancementContext, action: (String, InputStream) -> Unit) {
        val prefix = ExtractResources.DIRECTORY + "/"
        when {
            source.isDirectory -> source.resolve(ExtractResources.DIRECTORY).walk().filter { it.isFile }.sortedBy { it.path }
                .forEach { file -> file.inputStream().use { action(file.relativeTo(source).invariantSeparatorsPath.removePrefix(prefix), it) } }
            source.isFile -> JarFile(source).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }.sortedBy { it.name }
                    .forEach { entry -> jar.getInputStream(entry).use { action(entry.name.removePrefix(prefix), it) } }
            }
            else -> throw EnhancementException("Plugin ${context.plugin.name}: $source is neither a JAR nor a directory, so its resources cannot be extracted")
        }
    }

    /**
     * Writes the file into the data folder unless something, even a dangling symbolic link, already
     * exists there; true when it was written. The path is checked lexically, so a configuration file
     * the admin replaced with a link to a shared copy is simply left alone. The content goes to a
     * temporary file next to the destination first, so a failed copy never leaves half a file behind.
     */
    private fun extract(path: String, content: InputStream, context: EnhancementContext): Boolean {
        val dataFolder = context.plugin.dataFolder.toPath().toAbsolutePath().normalize()
        val destination = dataFolder.resolve(path).normalize()
        if (destination == dataFolder || !destination.startsWith(dataFolder))
            throw EnhancementException("Plugin ${context.plugin.name}: resource $path would be written outside the data folder")
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return false
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}", ".tmp")
        return try {
            Files.newOutputStream(temporary).use { content.copyTo(it) }
            Files.move(temporary, destination)
            true
        } catch (e: FileAlreadyExistsException) {
            false
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
