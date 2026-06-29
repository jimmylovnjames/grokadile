package com.grokadile.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.grokadile.core.logging.GrokLogger
import com.grokadile.service.accessibility.AccessibilityScreenController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * UI-automation surface for screen-acting agents. Event filtering/capabilities
 * are declared in res/xml/accessibility_service_config.xml. On connect it hands
 * its live connection to [AccessibilityScreenController] (the shared singleton
 * agents act through) and releases it on disconnect.
 */
@AndroidEntryPoint
class GrokadileAccessibilityService : AccessibilityService() {

    @Inject lateinit var logger: GrokLogger
    @Inject lateinit var screenController: AccessibilityScreenController

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenController.attach(this)
        logger.i(TAG, "Accessibility service connected — screen control available")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Agents pull screen state on demand via ScreenController; we don't need
        // to react to the firehose of events here. Kept lightweight by design.
    }

    override fun onInterrupt() {
        logger.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        screenController.detach()
        logger.i(TAG, "Accessibility service unbound — screen control unavailable")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        screenController.detach()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "A11yService"
    }
}
