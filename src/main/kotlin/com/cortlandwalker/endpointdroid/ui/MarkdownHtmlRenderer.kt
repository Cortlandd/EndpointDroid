package com.cortlandwalker.endpointdroid.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.intellij.plugins.markdown.ui.preview.html.MarkdownUtil
import org.intellij.plugins.markdown.ui.preview.SourceTextPreprocessor

/**
 * Converts markdown text into preview HTML using JetBrains Markdown plugin internals.
 *
 * The renderer intentionally accepts markdown as the source of truth; HTML is only
 * generated at the final display step by built-in Markdown tooling.
 */
internal object MarkdownHtmlRenderer {
    /**
     * Generates provider-specific preview HTML by applying the active markdown source preprocessor.
     */
    fun toPreviewHtml(
        project: Project,
        virtualFile: VirtualFile,
        markdown: String,
        preprocessor: SourceTextPreprocessor,
        createDocument: (String) -> com.intellij.openapi.editor.Document
    ): String {
        return runCatching {
            val document = createDocument(markdown)
            preprocessor.preprocessText(project, document, virtualFile)
        }.getOrElse {
            "<pre>${escapeHtml(markdown)}</pre>"
        }
    }

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
