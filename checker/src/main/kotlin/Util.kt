// ── Small text helpers ────────────────────────────────────────────────────────

internal fun escapeHtmlText(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

internal fun stripHtml(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()
