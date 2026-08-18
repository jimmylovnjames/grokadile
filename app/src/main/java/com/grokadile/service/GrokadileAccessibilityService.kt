package com.grokadile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.agent.ScreenActionProvider
import com.grokadile.domain.agent.ScreenContentProvider
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live accessibility surface for screen-reading + UI action agents.
 *
 * - Keeps a lightweight latest-root snapshot.
 * - Implements [ScreenContentProvider] + [ScreenActionProvider] so agents stay
 *   free of Android framework types.
 * - Event callback is non-blocking; heavy work only happens on explicit agent calls.
 */
@AndroidEntryPoint
class GrokadileAccessibilityService : AccessibilityService(),
    ScreenContentProvider, ScreenActionProvider {

    @Inject lateinit var logger: GrokLogger

    private val latestRoot = AtomicReference<AccessibilityNodeInfo?>(null)
    private val latestPackage = AtomicReference<String?>(null)
    private val latestTitle = AtomicReference<String?>(null)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        logger.i(TAG, "Accessibility service connected — screen read + actions live")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val candidate = event.source ?: rootInActiveWindow
                if (candidate != null) {
                    latestRoot.getAndSet(candidate)?.recycle()
                    latestPackage.set(
                        event.packageName?.toString() ?: candidate.packageName?.toString()
                    )
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
        val root = currentRoot() ?: return "ERROR: no active window root"
        return try {
            when (mode.lowercase()) {
                "text" -> buildText(root, maxNodes)
                "focused" -> buildFocused(root, maxDepth, maxNodes)
                else -> buildHierarchy(root, maxDepth, maxNodes)
            }
        } finally {
            // Do not recycle the cached latestRoot
        }
    }

    override fun screenshot(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            logger.w(TAG, "screenshot requires API 30+ (this device is ${Build.VERSION.SDK_INT})")
            return null
        }
        return captureScreenshotJpeg()
    }

    // ── ScreenActionProvider ───────────────────────────────────────────────

    override fun tap(x: Int, y: Int, durationMs: Long): String =
        dispatchClickGesture(x.toFloat(), y.toFloat(), durationMs.coerceIn(10L, 2000L), "tap")

    override fun longPress(x: Int, y: Int, durationMs: Long): String =
        dispatchClickGesture(x.toFloat(), y.toFloat(), durationMs.coerceIn(400L, 3000L), "longPress")

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): String {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 5000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture, "swipe ($fromX,$fromY)→($toX,$toY)")
    }

    override fun clickByText(text: String, exact: Boolean): String {
        val root = currentRoot() ?: return "ERROR: no active window root"
        val node = findNodeByText(root, text, exact)
            ?: return "ERROR: no node matching text \"$text\" (exact=$exact)"
        return performClickOnNode(node, "text:$text")
    }

    override fun clickById(viewId: String): String {
        val root = currentRoot() ?: return "ERROR: no active window root"
        val node = findNodeById(root, viewId)
            ?: return "ERROR: no node with viewId containing \"$viewId\""
        return performClickOnNode(node, "id:$viewId")
    }

    override fun typeText(text: String): String {
        val root = currentRoot() ?: return "ERROR: no active window root"
        val target = findFocused(root)?.takeIf { it.isEditable }
            ?: findFirstEditable(root)
            ?: return "ERROR: no focused or editable node found"

        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                "OK typed ${text.length} chars into ${nodeLabel(target)}"
            } else {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok2 = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (ok2) "OK typed (after focus) ${text.length} chars"
                else "ERROR: ACTION_SET_TEXT failed on ${nodeLabel(target)}"
            }
        } finally {
            // do not recycle focused node
        }
    }

    override fun globalAction(action: String): String {
        val code = when (action.uppercase()) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS", "RECENT" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "POWER_DIALOG", "POWER" -> GLOBAL_ACTION_POWER_DIALOG
            "LOCK_SCREEN" -> GLOBAL_ACTION_LOCK_SCREEN
            "TAKE_SCREENSHOT" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return "ERROR: unknown global action \"$action\". Use BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS|POWER_DIALOG"
        }
        val ok = performGlobalAction(code)
        return if (ok) "OK global $action" else "ERROR: performGlobalAction($action) returned false"
    }

    // ── Gesture helpers ────────────────────────────────────────────────────

    private fun dispatchClickGesture(x: Float, y: Float, durationMs: Long, label: String): String {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture, "$label @(${x.toInt()},${y.toInt()})")
    }

    private fun dispatchGestureSync(gesture: GestureDescription, label: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("ERROR: gesture timeout")
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.set("OK $label")
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.set("ERROR: $label cancelled")
                latch.countDown()
            }
        }, null)

        if (!ok) return "ERROR: dispatchGesture rejected for $label"
        latch.await(2, TimeUnit.SECONDS)
        return result.get()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshotJpeg(): ByteArray? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<ByteArray?>()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            holder.set(encodeScreenshotJpeg(screenshot))
                        } catch (t: Throwable) {
                            logger.w(TAG, "screenshot encode failed: ${t.message}", t)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        logger.w(TAG, "takeScreenshot failed code=$errorCode")
                        latch.countDown()
                    }
                },
            )
        } catch (t: Throwable) {
            logger.w(TAG, "takeScreenshot threw: ${t.message}", t)
            return null
        }
        latch.await(4, TimeUnit.SECONDS)
        return holder.get()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun encodeScreenshotJpeg(result: ScreenshotResult): ByteArray? {
        val buffer = result.hardwareBuffer
        try {
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            wrapped.recycle()
            if (software == null) return null
            val jpeg = compressScreenshot(software)
            software.recycle()
            return jpeg
        } finally {
            buffer.close()
        }
    }

    private fun compressScreenshot(src: Bitmap): ByteArray {
        val maxEdge = 1280
        val longest = maxOf(src.width, src.height)
        val scaled = if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
        if (scaled !== src) scaled.recycle()
        return out.toByteArray()
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo, label: String): String {
        if (node.isClickable || node.isCheckable) {
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) return "OK clicked $label via ACTION_CLICK (${nodeLabel(node)})"
        }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return "ERROR: empty bounds for $label"
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        return dispatchClickGesture(cx.toFloat(), cy.toFloat(), 50L, "tap-fallback $label")
    }

    // ── Node search ────────────────────────────────────────────────────────

    private fun currentRoot(): AccessibilityNodeInfo? =
        latestRoot.get() ?: rootInActiveWindow

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String,
        exact: Boolean,
    ): AccessibilityNodeInfo? {
        val target = text.trim()
        if (target.isEmpty()) return null
        return findFirst(root) { node ->
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            if (exact) {
                t.equals(target, ignoreCase = true) || d.equals(target, ignoreCase = true)
            } else {
                (t != null && t.contains(target, ignoreCase = true)) ||
                    (d != null && d.contains(target, ignoreCase = true))
            }
        }
    }

    private fun findNodeById(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val target = viewId.trim().lowercase()
        if (target.isEmpty()) return null
        return findFirst(root) { node ->
            val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase()
            id != null && (id == target || id.contains(target))
        }
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { it.isEditable && it.isVisibleToUser }

    private fun findFirst(
        node: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (pred(node) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirst(child, pred)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused || node.isAccessibilityFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocused(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val t = node.text?.toString()?.take(40)
        val id = node.viewIdResourceName?.substringAfterLast('/')
        return buildString {
            append(cls)
            if (!id.isNullOrBlank()) append(" #$id")
            if (!t.isNullOrBlank()) append(" \"$t\"")
        }
    }

    // ── Dump helpers ───────────────────────────────────────────────────────

    private fun buildText(root: AccessibilityNodeInfo, maxNodes: Int): String {
        val sb = StringBuilder()
        val visited = intArrayOf(0)
        val pkg = activePackage() ?: "?"
        sb.appendLine("=== SCREEN TEXT ($pkg) ===")
        collectText(root, sb, visited, maxNodes)
        if (visited[0] >= maxNodes) sb.appendLine("… truncated")
        return sb.toString()
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        visited: IntArray,
        maxNodes: Int,
    ) {
        if (visited[0] >= maxNodes) return
        visited[0]++
        val value = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
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

        @Volatile
        var instance: GrokadileAccessibilityService? = null
            private set
    }
}

/**
 * Hilt-provided [ScreenContentProvider] that forwards to the live service.
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

    override fun screenshot(): ByteArray? =
        GrokadileAccessibilityService.instance?.screenshot()
}

/**
 * Hilt-provided [ScreenActionProvider] that forwards to the live service.
 */
@Singleton
class LiveScreenActionProvider @Inject constructor() : ScreenActionProvider {
    private fun svc() = GrokadileAccessibilityService.instance

    override fun isAvailable(): Boolean = svc()?.isAvailable() == true

    override fun tap(x: Int, y: Int, durationMs: Long): String =
        svc()?.tap(x, y, durationMs)
            ?: "ERROR: Accessibility service not connected."

    override fun longPress(x: Int, y: Int, durationMs: Long): String =
        svc()?.longPress(x, y, durationMs)
            ?: "ERROR: Accessibility service not connected."

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): String =
        svc()?.swipe(fromX, fromY, toX, toY, durationMs)
            ?: "ERROR: Accessibility service not connected."

    override fun clickByText(text: String, exact: Boolean): String =
        svc()?.clickByText(text, exact)
            ?: "ERROR: Accessibility service not connected."

    override fun clickById(viewId: String): String =
        svc()?.clickById(viewId)
            ?: "ERROR: Accessibility service not connected."

    override fun typeText(text: String): String =
        svc()?.typeText(text)
            ?: "ERROR: Accessibility service not connected."

    override fun globalAction(action: String): String =
        svc()?.globalAction(action)
            ?: "ERROR: Accessibility service not connected."
}
