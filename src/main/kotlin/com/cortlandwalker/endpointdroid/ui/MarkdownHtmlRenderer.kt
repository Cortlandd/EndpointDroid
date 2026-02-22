package com.cortlandwalker.endpointdroid.ui

/**
 * Lightweight markdown-to-HTML renderer for EndpointDroid's details pane.
 *
 * This intentionally supports only the markdown features used by this plugin:
 * headings, bullets, inline code, bold text, and fenced code blocks.
 */
internal object MarkdownHtmlRenderer {

    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val inlineCodeRegex = Regex("`([^`]+)`")
    private val boldRegex = Regex("\\*\\*([^*]+)\\*\\*")

    /**
     * Converts markdown into styled HTML suitable for a Swing HTML view.
     */
    fun toHtml(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val body = StringBuilder()

        var inCodeBlock = false
        var inList = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    if (inList) {
                        body.append("</ul>")
                        inList = false
                    }
                    body.append("<pre><code>")
                    inCodeBlock = true
                } else {
                    body.append("</code></pre>")
                    inCodeBlock = false
                }
                continue
            }

            if (inCodeBlock) {
                body.append(escapeHtml(line)).append('\n')
                continue
            }

            if (trimmed.isEmpty()) {
                if (inList) {
                    body.append("</ul>")
                    inList = false
                }
                body.append("<br/>")
                continue
            }

            val heading = headingRegex.matchEntire(trimmed)
            if (heading != null) {
                if (inList) {
                    body.append("</ul>")
                    inList = false
                }
                val level = heading.groupValues[1].length
                body.append("<h").append(level).append('>')
                    .append(renderInline(heading.groupValues[2]))
                    .append("</h").append(level).append('>')
                continue
            }

            if (trimmed.startsWith("- ")) {
                if (!inList) {
                    body.append("<ul>")
                    inList = true
                }
                body.append("<li>")
                    .append(renderInline(trimmed.removePrefix("- ").trim()))
                    .append("</li>")
                continue
            }

            if (inList) {
                body.append("</ul>")
                inList = false
            }

            body.append("<p>").append(renderInline(trimmed)).append("</p>")
        }

        if (inList) {
            body.append("</ul>")
        }
        if (inCodeBlock) {
            body.append("</code></pre>")
        }

        return buildString {
            append("<html><head><style>")
            append("body{font-family:'SF Pro Text','Segoe UI',sans-serif;margin:10px;line-height:1.4;}")
            append("h1,h2,h3,h4,h5,h6{margin:8px 0 6px 0;}")
            append("p{margin:4px 0;}")
            append("ul{margin:4px 0 6px 18px;padding:0;}")
            append("li{margin:2px 0;}")
            append("code{font-family:'JetBrains Mono','Menlo',monospace;background:#2f3136;padding:1px 4px;border-radius:4px;}")
            append("pre{font-family:'JetBrains Mono','Menlo',monospace;background:#2f3136;padding:8px;border-radius:6px;overflow:auto;}")
            append("pre code{padding:0;background:transparent;}")
            append("</style></head><body>")
            append(body)
            append("</body></html>")
        }
    }

    /**
     * Renders supported inline markdown styles in a safe order.
     */
    private fun renderInline(text: String): String {
        val codeTokens = mutableListOf<String>()
        val withCodePlaceholders = inlineCodeRegex.replace(text) { match ->
            val token = "@@CODE_${codeTokens.size}@@"
            codeTokens += "<code>${escapeHtml(match.groupValues[1])}</code>"
            token
        }

        val escaped = escapeHtml(withCodePlaceholders)
        val withBold = boldRegex.replace(escaped) { match ->
            "<strong>${match.groupValues[1]}</strong>"
        }

        var restored = withBold
        for (i in codeTokens.indices) {
            restored = restored.replace("@@CODE_$i@@", codeTokens[i])
        }
        return restored
    }

    /**
     * Escapes text so markdown content can be safely embedded into HTML.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
