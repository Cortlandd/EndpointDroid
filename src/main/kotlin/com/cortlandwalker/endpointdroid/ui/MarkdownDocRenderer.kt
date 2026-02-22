package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint

/**
 * Builds a markdown document for a selected endpoint.
 *
 * The doc is intentionally verbose so users can quickly copy/paste runnable
 * snippets into HTTP Client files while export functionality is evolving.
 */
object MarkdownDocRenderer {
    /**
     * Renders endpoint documentation as markdown text.
     */
    fun render(ep: Endpoint): String {
        val base = ep.baseUrl ?: "{{host}}"
        val url = if (ep.path.startsWith("http://") || ep.path.startsWith("https://")) {
            ep.path
        } else {
            base.trimEnd('/') + ep.path
        }

        return buildString {
            appendLine("# Endpoint")
            appendLine()
            appendLine("- **Method:** ${ep.httpMethod.uppercase()}")
            appendLine("- **Path:** `${ep.path}`")
            appendLine("- **Service:** `${ep.serviceFqn}`")
            appendLine("- **Function:** `${ep.functionName}`")
            appendLine("- **Base URL:** `${ep.baseUrl ?: "{{host}}"}`")
            appendLine("- **Resolved URL:** `$url`")
            appendLine()
            appendLine("## Types")
            appendLine("- **Request:** ${ep.requestType ?: "None"}")
            appendLine("- **Response:** ${ep.responseType ?: "Unknown"}")
            appendLine()
            appendLine("## HTTP Client Draft")
            appendLine("```http")
            appendLine("${ep.httpMethod.uppercase()} $url")
            appendLine("Accept: application/json")
            appendLine("Authorization: Bearer {{token}}")
            appendLine("```")
            appendLine()
            appendLine("## Notes")
            appendLine("- Replace `{{token}}` with a real auth token when needed.")
            appendLine("- If Base URL is `{{host}}`, add `endpointdroid.yaml` in your project root.")
        }
    }
}
