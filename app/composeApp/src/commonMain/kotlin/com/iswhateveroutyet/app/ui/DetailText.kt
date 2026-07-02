package com.iswhateveroutyet.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

// Detail strings come from the checker HTML-escaped, with at most simple <a href="...">…</a>
// anchors (e.g. the Wikipedia link). Convert those to tappable links and unescape the rest.

private val ANCHOR = Regex("""<a\s+href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

private fun unescapeEntities(s: String): String = s
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&#39;", "'")
    .replace("&quot;", "\"")

fun detailToAnnotated(detail: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    for (match in ANCHOR.findAll(detail)) {
        append(unescapeEntities(detail.substring(index, match.range.first)))
        val url = match.groupValues[1]
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)),
            )
        ) {
            append(unescapeEntities(match.groupValues[2]))
        }
        index = match.range.last + 1
    }
    append(unescapeEntities(detail.substring(index)))
}
