package com.grokadile.domain.agent

/**
 * Agents the planner is allowed to dispatch to. [planner] itself is excluded
 * so a plan cannot recurse forever.
 */
object PlannerCatalog {
    val DISPATCHABLE_IDS: Set<String> = setOf(
        "echo",
        "grok.chat",
        "heartbeat",
        "screen_reader",
        "screen_tap",
        "screen_summary",
        "screen_wait",
        "screen_act",
        "scheduler",
        "notification_listener",
        "swarm",
        "vector_memory",
        "clipboard",
        "app_launch",
        "device_health",
    )

    const val SCHEMA_HINT: String = """
Known agents and typical payloads:
- echo: raw string or any JSON (returns it)
- grok.chat: {"prompt":"...","system":"...","model":"...","useMemory":true,"remember":true}
- screen_reader: {"mode":"text|hierarchy|focused","store":true}
- screen_tap: {"action":"tap|click_text|type|global|swipe","text":"...","x":0,"y":0,"name":"BACK|HOME"}
- screen_summary: {"mode":"hierarchy|text|focused","prompt":"optional question about the screen","store":true}
- screen_wait: {"mode":"appear|disappear|package","text":"...","packageName":"...","timeoutMs":15000,"pollMs":500,"exact":false}
- screen_act: {"goal":"Open Settings and turn Wi-Fi off","maxSteps":8,"timeoutMs":90000,"perception":"accessibility|vision","confirmWithWait":true}
- scheduler: {"type":"interval|cron","intervalMillis":60000,"expression":"0 9 * * *","targetAgentId":"echo","targetPayload":"{}"}
- notification_listener: {"mode":"list|match"}
- swarm: {"mode":"status|heartbeat"}
- vector_memory: {"mode":"remember|search|forget|stats|clear","text":"...","query":"..."}
- clipboard: {"mode":"get|set|clear","text":"..."}
- app_launch: {"mode":"launch|list|find","query":"Maps","packageName":"com.google.android.apps.maps"}
- device_health: {"mode":"status|retry_failed|prune"}
- heartbeat: {"intervalMillis":60000}
"""
}
