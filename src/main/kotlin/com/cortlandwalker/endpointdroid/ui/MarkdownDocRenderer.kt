package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint

object MarkdownDocRenderer {
    fun render(ep: Endpoint): String {
        val base = ep.baseUrl ?: "{{host}}"
        val url = if (ep.path.startsWith("http://") || ep.path.startsWith("https://")) {
            ep.path
        } else {
            base.trimEnd('/') + ep.path
        }

        return buildString {
            appendLine("# ${ep.httpMethod} ${ep.path}")
            appendLine()
            appendLine("- **Service:** ${ep.serviceFqn}")
            appendLine("- **Function:** ${ep.functionName}")
            appendLine("- **URL:** $url")
            appendLine()
            appendLine("## Types")
            appendLine("- **Request:** ${ep.requestType ?: "None"}")
            appendLine("- **Response:** ${ep.responseType ?: "Unknown"}")
        }
    }
}