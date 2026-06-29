package com.grokadile.domain.screen

/**
 * The on-screen capability surface for agents. Backed by the accessibility
 * service when it's enabled; [isAvailable] is false otherwise (so agents can
 * fail gracefully). All calls are safe to invoke from a background dispatcher —
 * the implementation marshals to the main thread as needed.
 */
interface ScreenController {
    val isAvailable: Boolean

    /** Capture the current foreground screen, or null if unavailable. */
    suspend fun snapshot(): ScreenSnapshot?

    /** Click an element (clickable self/ancestor, else a tap at its center). */
    suspend fun click(element: ScreenElement): Boolean

    /** Set the text of an editable element. */
    suspend fun setText(element: ScreenElement, text: String): Boolean

    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean

    suspend fun back(): Boolean
    suspend fun home(): Boolean
    suspend fun recents(): Boolean
}
