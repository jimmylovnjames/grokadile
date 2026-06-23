package com.grokadile.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.grokadile.core.logging.GrokLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Optional UI-automation surface. Event filtering/capabilities are declared in
 * res/xml/accessibility_service_config.xml. This class is intentionally thin:
 * it forwards observed screen state to agents that declare the
 * [com.grokadile.domain.agent.AgentCapability.ACCESSIBILITY] capability. Wire
 * that dispatch in [onAccessibilityEvent] when implementing screen-acting agents.
 */
@AndroidEntryPoint
class GrokadileAccessibilityService : AccessibilityService() {

    @Inject lateinit var logger: GrokLogger

    override fun onServiceConnected() {
        super.onServiceConnected()
        logger.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // TODO: route window/content events to ACCESSIBILITY-capable agents.
        // Kept lightweight by design; do not block this callback.
    }

    override fun onInterrupt() {
        logger.w(TAG, "Accessibility service interrupted")
    }

    private companion object {
        const val TAG = "A11yService"
    }
}
