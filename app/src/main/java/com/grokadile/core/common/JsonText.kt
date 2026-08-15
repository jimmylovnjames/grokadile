package com.grokadile.core.common

/**
 * Helpers for parsing LLM replies that often wrap JSON in markdown fences
 * or prepend a short prose sentence.
 */
object JsonText {
    fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end)
            s = s.trim()
        }
        return s
    }

    /** Best-effort extract of the first `{...}` object in [raw]. */
    fun extractObject(raw: String): String {
        val cleaned = stripFences(raw)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) return cleaned.substring(start, end + 1)
        return cleaned
    }
}
