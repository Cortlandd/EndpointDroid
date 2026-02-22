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
        val serviceLink = EndpointDocLinks.serviceUrl(ep.serviceFqn)
        val functionLink = EndpointDocLinks.functionUrl(ep.serviceFqn, ep.functionName)

        return buildString {
            appendLine("# Endpoint")
            appendLine()
            appendLine("- **Method:** ${ep.httpMethod.uppercase()}")
            appendLine("- **Path:** `${ep.path}`")
            appendLine("- **Service:** [`${ep.serviceFqn}`]($serviceLink)")
            appendLine("- **Function:** [`${ep.functionName}`]($functionLink)")
            appendLine("- **Source:** [Open function declaration]($functionLink)")
            appendLine("- **Base URL:** `${ep.baseUrl ?: "{{host}}"}`")
            appendLine("- **Resolved URL:** `$url`")
            appendLine()
            appendLine("## Types")
            appendLine("- **Request:** ${renderTypeLink(ep.requestType)}")
            appendLine("- **Response:** ${renderTypeLink(ep.responseType)}")
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

    /**
     * Renders a type as a clickable link when available.
     */
    private fun renderTypeLink(type: String?): String {
        if (type == null) return "None"
        val link = EndpointDocLinks.typeUrl(type)
        return "[`$type`]($link)"
    }
}
