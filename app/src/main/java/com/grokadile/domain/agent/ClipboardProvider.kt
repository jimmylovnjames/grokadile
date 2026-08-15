package com.grokadile.domain.agent

/**
 * Clipboard bridge so agents stay Android-free. Background reads may be empty
 * on API 29+ unless the app is in the foreground or has a focused input.
 */
interface ClipboardProvider {
    fun getText(): String?
    fun setText(text: String)
    fun clear()
}
