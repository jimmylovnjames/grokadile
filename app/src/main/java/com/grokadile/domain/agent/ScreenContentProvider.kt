package com.grokadile.domain.agent

/**
 * Bridge that lets ACCESSIBILITY-capable agents observe the current screen
 * without depending on Android framework types. Implemented by the live
 * [com.grokadile.service.GrokadileAccessibilityService] (or a test fake).
 *
 * All methods are safe to call from any thread; they return the most recent
 * snapshot or an error string if the service is not connected / no windows.
 */
interface ScreenContentProvider {

    /** True when the accessibility service is connected and has a root. */
    fun isAvailable(): Boolean

    /**
     * Human-readable dump of the current active window.
     * @param mode "text" (flat text only), "hierarchy" (indented tree), "focused" (focused node + ancestors)
     * @param maxDepth maximum tree depth (hierarchy mode)
     * @param maxNodes hard limit on nodes visited (prevents huge dumps)
     */
    fun dump(
        mode: String = "hierarchy",
        maxDepth: Int = 12,
        maxNodes: Int = 400,
    ): String

    /** Package name of the topmost/active window, or null. */
    fun activePackage(): String?

    /** Content description / title of the active window if available. */
    fun activeWindowTitle(): String?

    /**
     * JPEG bytes of the current display, or null if capture is unavailable
     * (service down, API 30 required, or takeScreenshot failed).
     *
     * Uses AccessibilityService.takeScreenshot — not MediaProjection.
     */
    fun screenshot(): ByteArray? = null
}
