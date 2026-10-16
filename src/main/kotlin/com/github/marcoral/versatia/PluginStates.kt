package com.github.marcoral.versatia

/**
 * The state stores the runtime hands to processors: one per enabled plugin, by plugin name, and
 * one for the runtime itself; see docs/runtime.md.
 */
class PluginStates : AutoCloseable {
    private val stores = mutableMapOf<String, ClosableStateStore>()

    /** The store that lives as long as the runtime. */
    val runtime = ClosableStateStore()

    fun of(pluginName: String) = stores.getOrPut(pluginName) { ClosableStateStore() }

    fun contains(pluginName: String) = pluginName in stores

    /** Forgets the plugin's state, closing its `AutoCloseable` entries last created first. */
    fun drop(pluginName: String) {
        stores.remove(pluginName)?.close()
    }

    /**
     * Drops every plugin's state, last created first, and then the runtime's; every store is closed
     * even when another fails, and the first failure is thrown with the rest suppressed.
     */
    override fun close() {
        val closes = stores.keys.toList().asReversed().map { name -> { drop(name) } } + runtime::close
        var failure: Exception? = null
        for (close in closes) try {
            close()
        } catch (e: Exception) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        failure?.let { throw it }
    }
}

class ClosableStateStore : StateStore, AutoCloseable {
    private val entries = LinkedHashMap<StateKey<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrPut(key: StateKey<T>, create: () -> T) = entries.getOrPut(key, create) as T

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: StateKey<T>) = entries[key] as T?

    override fun close() {
        val closeables = entries.values.toList().asReversed().filterIsInstance<AutoCloseable>()
        entries.clear()
        var failure: Exception? = null
        for (closeable in closeables) try {
            closeable.close()
        } catch (e: Exception) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        failure?.let { throw it }
    }
}
