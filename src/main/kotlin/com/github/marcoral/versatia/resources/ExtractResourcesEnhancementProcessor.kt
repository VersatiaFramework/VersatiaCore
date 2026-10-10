package com.github.marcoral.versatia.resources

import com.github.marcoral.versatia.EnhancementContext
import com.github.marcoral.versatia.EnhancementException
import com.github.marcoral.versatia.EnhancementProcessor
import com.github.marcoral.versatia.classLoader
import com.github.marcoral.versatia.engine.descriptor.EnhancedElement
import com.github.marcoral.versatia.engine.descriptor.PluginTarget
import java.io.File
import java.io.InputStream
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

    /** Writes the file into the data folder unless it already exists; true when it was written. */
    private fun extract(path: String, content: InputStream, context: EnhancementContext): Boolean {
        val dataFolder = context.plugin.dataFolder
        val destination = dataFolder.resolve(path)
        if (!destination.canonicalPath.startsWith(dataFolder.canonicalPath + File.separator))
            throw EnhancementException("Plugin ${context.plugin.name}: resource $path would be written outside the data folder")
        if (destination.exists()) return false
        destination.parentFile.mkdirs()
        destination.outputStream().use { content.copyTo(it) }
        return true
    }
}
