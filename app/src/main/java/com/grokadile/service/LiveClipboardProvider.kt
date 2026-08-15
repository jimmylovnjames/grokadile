package com.grokadile.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.grokadile.domain.agent.ClipboardProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveClipboardProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClipboardProvider {

    private val manager: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun getText(): String? {
        val clip = manager?.primaryClip ?: return null
        if (clip.itemCount < 1) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    override fun setText(text: String) {
        manager?.setPrimaryClip(ClipData.newPlainText("grokadile", text))
    }

    override fun clear() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            manager?.clearPrimaryClip()
        } else {
            manager?.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
