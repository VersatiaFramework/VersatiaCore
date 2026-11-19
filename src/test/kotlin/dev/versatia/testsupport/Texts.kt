package dev.versatia.testsupport

import dev.versatia.text.ServerPresentations
import dev.versatia.text.ServerStyles
import dev.versatia.text.TextImpl
import java.io.File
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
import org.bukkit.plugin.Plugin

/** VersatiaCore as the styles see it: a plugin whose data folder is `plugins/VersatiaCore` under [server], shipping [defaults] as the styles every plugin shares, or none. */
internal fun corePlugin(server: File, defaults: String? = null) =
    fakePlugin(FakeCore.NAME, dataFolder = server.resolve("plugins/${FakeCore.NAME}"), resources = listOfNotNull(defaults?.let { ServerStyles.FILE to it.trimIndent() }).toMap())

/** The `styles.yml` of the server under [server]. */
internal fun stylesFile(server: File) = server.resolve("plugins/${FakeCore.NAME}/${ServerStyles.FILE}")

/**
 * The styles of a server whose `styles.yml` holds [content], read as VersatiaCore reads them at its
 * start; without content there is no file and no style. [defaults] are the styles VersatiaCore
 * itself ships. The default [server] is a folder that does not exist, for tests that never touch
 * the file.
 */
internal fun serverStyles(server: File = File("build/tmp/no-server"), content: String? = null, defaults: String? = null): ServerStyles {
    if (content != null) stylesFile(server).apply { parentFile.mkdirs() }.writeText(content.trimIndent())
    return ServerStyles(corePlugin(server, defaults)).apply { reload() }
}

/** A plugin shipping [defaults] as its `styles.yml`, or none. */
internal fun pluginWithStyles(name: String, defaults: String? = null): Plugin =
    fakePlugin(name, resources = listOfNotNull(defaults?.let { ServerStyles.FILE to it.trimIndent() }).toMap())

/**
 * The `Text` made for the plugin [name] over [styles] and the presentations of [server]; [defaults]
 * is the `styles.yml` that plugin ships, handed over by `reload()`.
 */
internal fun textOf(styles: ServerStyles = serverStyles(), name: String = "Shop", defaults: String? = null, server: ServerPresentations = ServerPresentations()) =
    TextImpl(styles, server, pluginWithStyles(name, defaults))

/**
 * The text of [component] in pieces, each with the colour a player sees it in, whatever the
 * nesting; neighbours of one colour are one piece.
 */
internal fun shownColours(component: Component): List<Pair<String, TextColor?>> {
    val pieces = ArrayList<Pair<String, TextColor?>>()
    fun walk(current: Component, inherited: TextColor?) {
        val color = current.color() ?: inherited
        if (current is TextComponent && current.content().isNotEmpty()) {
            val last = pieces.lastOrNull()
            if (last != null && last.second == color) pieces[pieces.lastIndex] = last.first + current.content() to color else pieces += current.content() to color
        }
        current.children().forEach { walk(it, color) }
    }
    walk(component, null)
    return pieces
}
