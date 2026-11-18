package dev.versatia.di

/**
 * A bean of VersatiaCore that is told once the plugin it belongs to has started: its beans exist,
 * its [Reloadable] ones are loaded and its start functions have run, so everything they register
 * is in place. A prototype instance made later is told as soon as it has been loaded. What
 * [started] throws fails the start. See docs/runtime.md.
 */
internal interface StartAware {
    fun started()
}
