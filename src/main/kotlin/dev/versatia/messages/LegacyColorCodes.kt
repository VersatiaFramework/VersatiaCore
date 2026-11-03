package dev.versatia.messages

/**
 * Finds the legacy colour codes, `&a` or `§l`, that MiniMessage shows as text. A code counts at the
 * start of a line, after anything but a letter or digit, or right after another code, so `R&D` and
 * `B&B` are text while `&a&lSale` holds two codes.
 */
internal object LegacyColorCodes {
    private val RUN = Regex("""(?<![\p{L}\p{N}])(?:[&§][0-9a-fk-or])+""", RegexOption.IGNORE_CASE)

    private val TAGS = mapOf(
        '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua", '4' to "dark_red", '5' to "dark_purple",
        '6' to "gold", '7' to "gray", '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua", 'c' to "red",
        'd' to "light_purple", 'e' to "yellow", 'f' to "white", 'k' to "obfuscated", 'l' to "bold", 'm' to "strikethrough",
        'n' to "underlined", 'o' to "italic", 'r' to "reset",
    )

    /** The codes in [text], each once, as written. */
    fun find(text: String) = RUN.findAll(text).flatMap { it.value.chunked(2) }.distinct().toList()

    /** The MiniMessage tag replacing [code]: `&a` is `<green>`. */
    fun tag(code: String) = "<${TAGS.getValue(code[1].lowercaseChar())}>"
}
