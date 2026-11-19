package dev.versatia.engine.descriptor

/**
 * The class a descriptor names by its Kotlin qualified name, as a `TypeReference` does: a Kotlin
 * type the JVM knows under a Java name, `kotlin.String` or `kotlin.Int`, is that Java class, boxed,
 * and a nested class is found under each binary name it may have. Throws a
 * [ClassNotFoundException] naming [qualifiedName] when there is none; the class is not initialized.
 */
internal fun loadDescribedType(qualifiedName: String, classLoader: ClassLoader): Class<*> {
    KOTLIN_TYPES[qualifiedName]?.let { return it }
    var name = qualifiedName
    while (true) {
        try {
            return Class.forName(name, false, classLoader)
        } catch (e: ClassNotFoundException) {
            val dot = name.lastIndexOf('.')
            if (dot < 0) throw ClassNotFoundException(qualifiedName, e)
            name = name.substring(0, dot) + "$" + name.substring(dot + 1)
        }
    }
}

private val KOTLIN_TYPES = listOf(
    Any::class, String::class, CharSequence::class, Number::class, Int::class, Long::class, Short::class, Byte::class, Double::class, Float::class,
    Char::class, Boolean::class, Throwable::class, Comparable::class, Enum::class, Annotation::class, Iterable::class, Collection::class,
    List::class, Set::class, Map::class, Map.Entry::class, Iterator::class,
).associate { it.qualifiedName!! to it.javaObjectType }
