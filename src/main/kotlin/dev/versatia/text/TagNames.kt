package dev.versatia.text

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags

/** What the parser knows of tag names, whatever the text. */
internal object TagNames {
    val STANDARD: TagResolver = StandardTags.defaults()

    /** The names a plugin's tag and a style may have, in either case: `serverTag` and `servertag` are one name. */
    val OWN = Regex("[A-Za-z0-9_-]+")

    /** Starts the names of the tags the parser makes itself. */
    const val RESERVED = "versatia-"

    /** A name as MiniMessage reads one from angle brackets, in lower case. */
    private val READ = Regex("[!?#]?[a-z0-9_-]*")

    /** [name] as MiniMessage hands it over whatever a text writes, which is how a tag or a style is found: in lower case. */
    fun key(name: String) = name.lowercase()

    /**
     * Whether angle brackets around [name] were meant as a tag, so that one nobody answers is a
     * mistake and not text: the name has a letter. `<3>`, `<->` and `<?>` are text.
     */
    fun isMeantAsTag(name: String) = READ.matches(name) && name.any { it in 'a'..'z' }
}
