package com.grokadile.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.agent.ScreenContentProvider
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live accessibility surface for screen-reading (and later gesture) agents.
 *
 * - Keeps a lightweight latest-root snapshot.
 * - Implements [ScreenContentProvider] so agents stay Android-free.
 * - Event callback is deliberately non-blocking; heavy work happens only when
 *   an agent explicitly calls [dump].
 */
@AndroidEntryPoint
class GrokadileAccessibilityService : AccessibilityService(), ScreenContentProvider {

    @Inject lateinit var logger: GrokLogger

    // Latest root we observed. Cleared on disconnect.
    private val latestRoot = AtomicReference<AccessibilityNodeInfo?>(null)
    private val latestPackage = AtomicReference<String?>(null)
    private val latestTitle = AtomicReference<String?>(null)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        logger.i(TAG, "Accessibility service connected — screen reading live")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Only care about window / content changes that give us a usable root.
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Prefer the source node if present, otherwise fall back to rootInActiveWindow.
                val candidate = event.source ?: rootInActiveWindow
                if (candidate != null) {
                    // Recycle previous to avoid leaks.
                    latestRoot.getAndSet(candidate)?.recycle()
                    latestPackage.set(event.packageName?.toString() ?: candidate.packageName?.toString())
                    latestTitle.set(
                        event.contentDescription?.toString()
                            ?: candidate.contentDescription?.toString()
                            ?: candidate.text?.toString()
                    )
                }
            }
        }
    }

    override fun onInterrupt() {
        logger.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        latestRoot.getAndSet(null)?.recycle()
        if (instance === this) instance = null
        logger.i(TAG, "Accessibility service destroyed")
    }

    // ── ScreenContentProvider ──────────────────────────────────────────────

    override fun isAvailable(): Boolean =
        instance != null && (latestRoot.get() != null || rootInActiveWindow != null)

    override fun activePackage(): String? =
        latestPackage.get() ?: rootInActiveWindow?.packageName?.toString()

    override fun activeWindowTitle(): String? = latestTitle.get()

    override fun dump(mode: String, maxDepth: Int, maxNodes: Int): String {
        val root = latestRoot.get() ?: rootInActiveWindow
            ?: return "ERROR: Accessibility service has no active window root. " +
                "Is the service enabled and a window visible?"

        return try {
            when (mode.lowercase()) {
                "text", "flat" -> buildFlatText(root, maxNodes)
                "focused" -> buildFocused(root, maxDepth, maxNodes)
                else -> buildHierarchy(root, maxDepth, maxNodes) // default "hierarchy"
            }
        } catch (t: Throwable) {
            logger.e(TAG, "dump failed", t)
            "ERROR: dump failed — ${t.message}"
        }
    }

    // ── dump helpers ───────────────────────────────────────────────────────

    private fun buildFlatText(root: AccessibilityNodeInfo, maxNodes: Int): String {
        val sb = StringBuilder()
        val visited = intArrayOf(0)
        collectText(root, sb, visited, maxNodes)
        val pkg = activePackage() ?: "?"
        val title = activeWindowTitle()
        return buildString {
            appendLine("=== SCREEN TEXT ($pkg) ===")
            if (!title.isNullOrBlank()) appendLine("title: $title")
            appendLine()
            append(sb.toString().trim())
            if (visited[0] >= maxNodes) appendLine("\n… truncated at $maxNodes nodes")
        }
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        visited: IntArray,
        maxNodes: Int,
    ) {
        if (visited[0] >= maxNodes) return
        visited[0]++

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val value = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> null
        }
        if (!value.isNullOrEmpty() && node.isVisibleToUser) {
            out.appendLine(value)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectText(child, out, visited, maxNodes)
            } finally {
                child.recycle()
            }
        }
    }

    private fun buildHierarchy(root: AccessibilityNodeInfo, maxDepth: Int, maxNodes: Int): String {
        val sb = StringBuilder()
        val visited = intArrayOf(0)
        val pkg = activePackage() ?: "?"
        val title = activeWindowTitle()
        sb.appendLine("=== SCREEN HIERARCHY ($pkg) ===")
        if (!title.isNullOrBlank()) sb.appendLine("title: $title")
        sb.appendLine()
        walk(root, sb, depth = 0, maxDepth = maxDepth, visited = visited, maxNodes = maxNodes)
        if (visited[0] >= maxNodes) sb.appendLine("… truncated at $maxNodes nodes")
        return sb.toString()
    }

    private fun buildFocused(root: AccessibilityNodeInfo, maxDepth: Int, maxNodes: Int): String {
        val focused = findFocused(root)
            ?: return "No focused node found.\n\n" + buildHierarchy(root, maxDepth, maxNodes)
        val sb = StringBuilder()
        sb.appendLine("=== FOCUSED NODE ===")
        sb.appendLine(nodeSummary(focused, depth = 0))
        sb.appendLine()
        sb.appendLine("--- context hierarchy ---")
        sb.append(buildHierarchy(root, maxDepth = 6, maxNodes = 120))
        return sb.toString()
    }

    private fun findFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused || node.isAccessibilityFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocused(child)
            if (found != null) {
                // do not recycle the returned focused node
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int,
        maxDepth: Int,
        visited: IntArray,
        maxNodes: Int,
    ) {
        if (visited[0] >= maxNodes || depth > maxDepth) return
        visited[0]++

        out.appendLine(nodeSummary(node, depth))

        if (depth >= maxDepth) return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walk(child, out, depth + 1, maxDepth, visited, maxNodes)
            } finally {
                child.recycle()
            }
        }
    }

    private fun nodeSummary(node: AccessibilityNodeInfo, depth: Int): String {
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()?.take(80)?.replace("\n", "⏎")
        val desc = node.contentDescription?.toString()?.take(60)?.replace("\n", "⏎")
        val id = node.viewIdResourceName?.substringAfterLast('/')
        val bounds = Rect().also { node.getBoundsInScreen(it) }

        val flags = buildList {
            if (node.isClickable) add("click")
            if (node.isEditable) add("edit")
            if (node.isCheckable) add(if (node.isChecked) "checked" else "checkable")
            if (node.isFocused) add("focused")
            if (node.isSelected) add("selected")
            if (!node.isVisibleToUser) add("hidden")
            if (node.isScrollable) add("scroll")
        }.joinToString(",")

        return buildString {
            append(indent)
            append(cls)
            if (!id.isNullOrBlank()) append(" #$id")
            if (!text.isNullOrBlank()) append(" \"$text\"")
            else if (!desc.isNullOrBlank()) append(" [$desc]")
            if (flags.isNotEmpty()) append(" {$flags}")
            append(" ${bounds.width()}x${bounds.height()}@(${bounds.left},${bounds.top})")
        }
    }

    companion object {
        private const val TAG = "A11yService"

        /** Live instance while the service is connected. Null when disabled/destroyed. */
        @Volatile
        var instance: GrokadileAccessibilityService? = null
            private set
    }
}

/**
 * Hilt-provided [ScreenContentProvider] that forwards to the live service
 * (or returns a clear error when the service is not running).
 */
@Singleton
class LiveScreenContentProvider @Inject constructor() : ScreenContentProvider {
    override fun isAvailable(): Boolean =
        GrokadileAccessibilityService.instance?.isAvailable() == true

    override fun dump(mode: String, maxDepth: Int, maxNodes: Int): String =
        GrokadileAccessibilityService.instance?.dump(mode, maxDepth, maxNodes)
            ?: "ERROR: Accessibility service not connected. " +
            "Enable it in Settings → Accessibility → Grokadile."

    override fun activePackage(): String? =
        GrokadileAccessibilityService.instance?.activePackage()

    override fun activeWindowTitle(): String? =
        GrokadileAccessibilityService.instance?.activeWindowTitle()
}
