package com.grokadile.domain.screen

/** Screen-space rectangle of a UI element. */
data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * A single actionable node observed on screen. Carries enough to be re-located
 * in a fresh accessibility tree (by [viewId] or [text]) plus [bounds] as a tap
 * fallback — agents never hold live node references across an async LLM call.
 */
data class ScreenElement(
    val index: Int,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val packageName: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val bounds: ScreenBounds,
)

/** Flattened snapshot of the current foreground screen. */
data class ScreenSnapshot(
    val packageName: String?,
    val elements: List<ScreenElement>,
    val capturedAt: Long = System.currentTimeMillis(),
)
