package com.cortlandwalker.endpointdroid.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Converts markdown text into lightweight HTML for the details pane.
 *
 * Markdown remains the source of truth. This renderer only transforms markdown
 * into a Swing-friendly HTML representation for display.
 */
internal object MarkdownHtmlRenderer {

    /**
     * Converts markdown to HTML. The project/file parameters are accepted to keep call sites stable.
     */
    fun toHtml(project: Project, virtualFile: VirtualFile, markdown: String): String {
        val renderedBody = runCatching {
            val flavour = GFMFlavourDescriptor()
            val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour).generateHtml()
        }.getOrElse {
            "<pre>${escapeHtml(markdown)}</pre>"
        }

        // project/virtualFile are intentionally unused for this lightweight renderer path.
        @Suppress("UNUSED_VARIABLE")
        val ignore = project to virtualFile

        return "<html><body>${normalizeTablesForSwing(addTableBorders(renderedBody))}</body></html>"
    }

    /**
     * Escapes plain text fallback content into safe HTML.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    /**
     * Adds explicit border attributes so table cells are visible in Swing HTML rendering.
     */
    private fun addTableBorders(html: String): String {
        return Regex("<table(\\s[^>]*)?>")
            .replace(html) { match ->
                val attrs = match.groupValues.getOrElse(1) { "" }
                if (attrs.contains("border=", ignoreCase = true)) {
                    match.value
                } else {
                    "<table$attrs border=\"1\" cellspacing=\"0\" cellpadding=\"4\" rules=\"all\" frame=\"box\">"
                }
            }
    }

    /**
     * Normalizes table tags that Swing's HTML implementation handles inconsistently.
     */
    private fun normalizeTablesForSwing(html: String): String {
        val withoutSections = html
            .replace("<thead>", "")
            .replace("</thead>", "")
            .replace("<tbody>", "")
            .replace("</tbody>", "")

        val withThBorders = Regex("<th(\\s[^>]*)?>")
            .replace(withoutSections) { match ->
                val attrs = match.groupValues.getOrElse(1) { "" }
                if (attrs.contains("border=", ignoreCase = true)) match.value else "<th$attrs border=\"1\">"
            }

        return Regex("<td(\\s[^>]*)?>")
            .replace(withThBorders) { match ->
                val attrs = match.groupValues.getOrElse(1) { "" }
                if (attrs.contains("border=", ignoreCase = true)) match.value else "<td$attrs border=\"1\">"
            }
    }
}
