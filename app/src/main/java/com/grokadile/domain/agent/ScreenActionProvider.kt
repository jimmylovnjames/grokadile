package com.grokadile.domain.agent

/**
 * Action surface for ACCESSIBILITY-capable agents. Complements
 * [ScreenContentProvider] with the ability to drive the UI.
 *
 * Implemented by the live [com.grokadile.service.GrokadileAccessibilityService].
 * All methods are safe to call from background threads; they block briefly
 * (gesture dispatch has a short timeout) and return a human-readable result
 * string that agents can put into task output / memory.
 */
interface ScreenActionProvider {

    /** True when the accessibility service is connected and can perform gestures. */
    fun isAvailable(): Boolean

    /**
     * Tap (click) at absolute screen coordinates.
     * @return success / error message
     */
    fun tap(x: Int, y: Int, durationMs: Long = 50L): String

    /**
     * Long-press at absolute screen coordinates.
     */
    fun longPress(x: Int, y: Int, durationMs: Long = 800L): String

    /**
     * Swipe from (fromX,fromY) to (toX,toY).
     */
    fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long = 300L,
    ): String

    /**
     * Find a visible node whose text or contentDescription contains [text]
     * (or equals if exact=true) and perform ACTION_CLICK on it.
     * Falls back to a gesture tap on its center bounds if the action fails.
     */
    fun clickByText(text: String, exact: Boolean = false): String

    /**
     * Find a node by viewIdResourceName (partial match after last '/') and click it.
     */
    fun clickById(viewId: String): String

    /**
     * Type [text] into the currently focused editable node (or the first editable
     * node found). Uses ACTION_SET_TEXT when possible.
     */
    fun typeText(text: String): String

    /**
     * Perform a global action: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG.
     */
    fun globalAction(action: String): String
}
