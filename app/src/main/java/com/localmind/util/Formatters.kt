package com.localmind.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return if (i <= 1) String.format(Locale.US, "%.0f %s", v, units[i])
        else String.format(Locale.US, "%.2f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0) return "—"
        return size(bytesPerSecond.toLong()) + "/s"
    }

    fun duration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun eta(remainingBytes: Long, bytesPerSecond: Double): String {
        if (bytesPerSecond <= 1.0) return "计算中…"
        val seconds = (remainingBytes / bytesPerSecond).toLong()
        return "剩余 " + duration(seconds * 1000)
    }

    fun time(epochMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

    fun dateTime(epochMs: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun params(count: Long): String = when {
        count <= 0 -> "—"
        count >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", count / 1_000_000_000.0)
        count >= 1_000_000L -> String.format(Locale.US, "%.0fM", count / 1_000_000.0)
        else -> String.format(Locale.US, "%.0fK", count / 1_000.0)
    }

    fun tokensPerSecond(tokens: Int, millis: Long): Double {
        if (millis <= 0) return 0.0
        return tokens * 1000.0 / millis
    }
}

/** Small helpers used by the OpenAI-compatible API and the downloader. */
object TextUtils {
    fun preview(text: String, max: Int = 120): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }

    fun randomId(prefix: String = "id"): String =
        prefix + "-" + java.util.UUID.randomUUID().toString().take(8)

    fun randomApiKey(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return "sk-lm-" + (1..32).map { alphabet.random() }.joinToString("")
    }

    /** Extracts fenced code blocks and inline code so the chat bubble can render them. */
    fun splitCodeSegments(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var i = 0
        val fence = Regex("```([a-zA-Z0-9+#._-]*)\\n?([\\s\\S]*?)```")
        for (m in fence.findAll(text)) {
            if (m.range.first > i) out += Segment.Text(text.substring(i, m.range.first))
            out += Segment.Code(m.groupValues[1], m.groupValues[2].trimEnd())
            i = m.range.last + 1
        }
        if (i < text.length) out += Segment.Text(text.substring(i))
        return out.ifEmpty { listOf(Segment.Text(text)) }
    }

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Code(val language: String, val code: String) : Segment
    }

    /**
     * Splits chain-of-thought from the final answer, DeepSeek-R1 / Qwen3 style.
     *
     * Handles both the closed form (`reasoning ...  answer`) and the
     * **still-streaming** form, where the closing tag has not arrived yet — in that
     * case everything after the opening tag is reasoning, which is exactly what you
     * want to hide while the model is thinking.
     */
    data class Reasoning(val reasoning: String, val answer: String, val thinking: Boolean)

    private val thinkOpen = Regex("<(?:redacted_)?think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val thinkClose = Regex("</(?:redacted_)?think(?:ing)?>", RegexOption.IGNORE_CASE)

    fun splitReasoning(text: String): Reasoning {
        if (text.isEmpty()) return Reasoning("", "", false)

        val open = thinkOpen.find(text)
        if (open == null) return Reasoning("", text, false)

        val before = text.substring(0, open.range.first)
        val afterOpen = text.substring(open.range.last + 1)
        val close = thinkClose.find(afterOpen)

        return if (close == null) {
            // still thinking: nothing final has been produced yet
            Reasoning((before + afterOpen).trim(), "", true)
        } else {
            val reasoning = (before + afterOpen.substring(0, close.range.first)).trim()
            val answer = afterOpen.substring(close.range.last + 1).trim()
            Reasoning(reasoning, answer, false)
        }
    }

    /** True when the text starts a chain of thought that has not closed yet. */
    fun isThinking(text: String): Boolean {
        val open = thinkOpen.find(text) ?: return false
        return thinkClose.find(text.substring(open.range.last + 1)) == null
    }
}
