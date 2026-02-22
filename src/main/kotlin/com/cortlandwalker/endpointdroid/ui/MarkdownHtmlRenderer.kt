package com.cortlandwalker.endpointdroid.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil

/**
 * Converts markdown text into preview HTML using JetBrains Markdown plugin internals.
 *
 * The renderer intentionally accepts markdown as the source of truth; HTML is only
 * generated at the final display step by built-in Markdown tooling.
 */
internal object MarkdownHtmlRenderer {

    /**
     * Generates preview HTML for markdown text using the active IDE markdown implementation.
     */
    fun toHtml(project: Project, virtualFile: VirtualFile, markdown: String): String {
        return runCatching {
            MarkdownUtil.generateMarkdownHtml(virtualFile, markdown, project)
        }.getOrElse { _ ->
            "<pre>${escapeHtml(markdown)}</pre>"
        }
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
}
