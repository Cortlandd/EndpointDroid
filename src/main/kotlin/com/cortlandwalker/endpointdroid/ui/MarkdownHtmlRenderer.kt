package com.cortlandwalker.endpointdroid.ui

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown renderer for EndpointDroid details.
 *
 * Uses the IntelliJ/JetBrains markdown parser so output behavior stays aligned
 * with JetBrains tooling instead of custom markdown rules.
 */
internal object MarkdownHtmlRenderer {

    /**
     * Converts markdown to HTML and wraps it in a simple document shell.
     * Falls back to plain escaped text when markdown conversion fails.
     */
    fun toHtml(markdown: String): String {
        val renderedBody = runCatching {
            val flavour = GFMFlavourDescriptor()
            val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour).generateHtml()
        }.getOrElse {
            "<pre>${escapeHtml(markdown)}</pre>"
        }
        val bodyWithTables = addTableBorders(renderedBody)

        // Avoid CSS to prevent Swing HTML parser edge-case crashes.
        return "<html><body>$bodyWithTables</body></html>"
    }

    /**
     * Escapes text for safe HTML fallback rendering.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    /**
     * Adds legacy HTML table border attributes so markdown tables remain readable in Swing HTML.
     */
    private fun addTableBorders(html: String): String {
        return Regex("<table(\\s[^>]*)?>")
            .replace(html) { match ->
                val attrs = match.groupValues.getOrElse(1) { "" }
                if (attrs.contains("border=", ignoreCase = true)) {
                    match.value
                } else {
                    "<table$attrs border=\"1\" cellspacing=\"0\" cellpadding=\"4\">"
                }
            }
    }
}
