package com.grokadile.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.grokadile.core.common.DispatcherProvider
import com.grokadile.domain.screen.ScreenBounds
import com.grokadile.domain.screen.ScreenController
import com.grokadile.domain.screen.ScreenElement
import com.grokadile.domain.screen.ScreenSnapshot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [ScreenController] backed by the live accessibility service. The service
 * [attach]es/[detach]es itself as it connects/disconnects; this singleton is
 * shared with agents (which inject the [ScreenController] interface), so they
 * act through the same connection. Node-tree work runs on the main thread.
 */
@Singleton
class AccessibilityScreenController @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : ScreenController {

    @Volatile private var service: AccessibilityService? = null

    fun attach(service: AccessibilityService) {
        this.service = service
    }

    fun detach() {
        this.service = null
    }

    override val isAvailable: Boolean get() = service != null

    override suspend fun snapshot(): ScreenSnapshot? = withContext(dispatchers.main) {
        val svc = service ?: return@withContext null
        val root = svc.rootInActiveWindow ?: return@withContext null
        val elements = ArrayList<ScreenElement>(MAX_ELEMENTS)
        collect(root, elements, 0)
        ScreenSnapshot(packageName = root.packageName?.toString(), elements = elements)
    }

    override suspend fun click(element: ScreenElement): Boolean = withContext(dispatchers.main) {
        val svc = service ?: return@withContext false
        val root = svc.rootInActiveWindow ?: return@withContext false
        val node = locate(root, element)
        val target = node?.let { clickableSelfOrAncestor(it) }
        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            // Fallback: tap the element's center coordinates.
            dispatchTap(svc, element.bounds.centerX.toFloat(), element.bounds.centerY.toFloat())
        }
    }

    override suspend fun setText(element: ScreenElement, text: String): Boolean =
        withContext(dispatchers.main) {
            val svc = service ?: return@withContext false
            val root = svc.rootInActiveWindow ?: return@withContext false
            val node = locate(root, element) ?: return@withContext false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

    override suspend fun tap(x: Int, y: Int): Boolean = withContext(dispatchers.main) {
        val svc = service ?: return@withContext false
        dispatchTap(svc, x.toFloat(), y.toFloat())
    }

    override suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean = withContext(dispatchers.main) {
        val svc = service ?: return@withContext false
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        dispatchGesture(svc, gesture)
    }

    override suspend fun back(): Boolean = global(AccessibilityService.GLOBAL_ACTION_BACK)
    override suspend fun home(): Boolean = global(AccessibilityService.GLOBAL_ACTION_HOME)
    override suspend fun recents(): Boolean = global(AccessibilityService.GLOBAL_ACTION_RECENTS)

    private suspend fun global(action: Int): Boolean = withContext(dispatchers.main) {
        service?.performGlobalAction(action) ?: false
    }

    // --- node tree helpers (main thread) -----------------------------------

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<ScreenElement>, depth: Int) {
        if (node == null || out.size >= MAX_ELEMENTS || depth > MAX_DEPTH) return
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val clickable = node.isClickable
        val editable = node.isEditable
        if (node.isVisibleToUser && (clickable || editable || text != null || desc != null)) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            out.add(
                ScreenElement(
                    index = out.size,
                    text = text,
                    contentDescription = desc,
                    viewId = node.viewIdResourceName,
                    className = node.className?.toString(),
                    packageName = node.packageName?.toString(),
                    clickable = clickable,
                    editable = editable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    bounds = ScreenBounds(r.left, r.top, r.right, r.bottom),
                ),
            )
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    private fun locate(root: AccessibilityNodeInfo, element: ScreenElement): AccessibilityNodeInfo? {
        element.viewId?.let { vid ->
            root.findAccessibilityNodeInfosByViewId(vid)?.firstOrNull()?.let { return it }
        }
        element.text?.let { t ->
            root.findAccessibilityNodeInfosByText(t)?.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        return null
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun dispatchTap(svc: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        // Fire-and-forget tap; dispatch acceptance is a good-enough signal here.
        return svc.dispatchGesture(gesture, null, null)
    }

    private suspend fun dispatchGesture(
        svc: AccessibilityService,
        gesture: GestureDescription,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }
        val accepted = svc.dispatchGesture(gesture, callback, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }

    private companion object {
        const val MAX_ELEMENTS = 60
        const val MAX_DEPTH = 40
        const val MAX_ANCESTOR_HOPS = 8
        const val TAP_DURATION_MS = 50L
    }
}
