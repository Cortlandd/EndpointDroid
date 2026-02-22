package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint

/**
 * Builds a markdown document for a selected endpoint.
 *
 * The doc is intentionally verbose so users can quickly copy/paste runnable
 * snippets into HTTP Client files while export functionality is evolving.
 */
internal object MarkdownDocRenderer {
    /**
     * Renders endpoint documentation as markdown text.
     */
    fun render(ep: Endpoint, details: EndpointDocDetails = EndpointDocDetails.empty()): String {
        val base = ep.baseUrl ?: "{{host}}"
        val url = if (ep.path.startsWith("http://") || ep.path.startsWith("https://")) {
            ep.path
        } else {
            base.trimEnd('/') + ep.path
        }
        val requestUrl = buildRequestUrl(url, details)
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
            if (details.sourceFile != null && details.sourceLine != null) {
                appendLine("- **Source File:** `${details.sourceFile}:${details.sourceLine}`")
            }
            appendLine("- **Base URL:** `${ep.baseUrl ?: "{{host}}"}`")
            appendLine("- **Resolved URL:** `$url`")
            appendLine()
            appendLine("## Types")
            appendLine("- **Request:** ${renderTypeLink(ep.requestType)}")
            appendLine("- **Response:** ${renderTypeLink(ep.responseType)}")
            appendLine()
            appendLine("## Parameters")
            appendLine("- **Path Params:** ${renderNames(details.pathParams)}")
            appendLine("- **Query Params:** ${renderNames(details.queryParams)}${if (details.hasQueryMap) " + `@QueryMap`" else ""}")
            appendLine("- **Header Params:** ${renderNames(details.headerParams)}${if (details.hasHeaderMap) " + `@HeaderMap`" else ""}")
            appendLine("- **Form Fields:** ${renderNames(details.fieldParams)}${if (details.hasFieldMap) " + `@FieldMap`" else ""}")
            appendLine("- **Multipart Parts:** ${renderNames(details.partParams)}${if (details.hasPartMap) " + `@PartMap`" else ""}")
            appendLine("- **Dynamic URL (`@Url`):** ${if (details.hasDynamicUrl) "Yes" else "No"}")
            appendLine()
            appendLine("## HTTP Client Draft")
            appendLine("```http")
            appendLine("${ep.httpMethod.uppercase()} $requestUrl")
            appendLine("Accept: application/json")
            details.staticHeaders.forEach { headerLine ->
                appendLine(headerLine)
            }
            details.headerParams
                .filterNot { it.equals("Authorization", ignoreCase = true) }
                .forEach { headerName ->
                    appendLine("$headerName: {{${toPlaceholder(headerName)}}}")
                }
            when (details.authRequirement) {
                EndpointDocDetails.AuthRequirement.REQUIRED -> {
                    val hasStaticAuthorization = details.staticHeaders.any { header ->
                        header.substringBefore(':').trim().equals("Authorization", ignoreCase = true)
                    }
                    if (!hasStaticAuthorization) {
                        appendLine("Authorization: Bearer {{token}}")
                    }
                }

                EndpointDocDetails.AuthRequirement.OPTIONAL ->
                    appendLine("# Optional: Authorization: Bearer {{token}}")

                EndpointDocDetails.AuthRequirement.NONE -> Unit
            }
            if (details.hasBody || ep.requestType != null) {
                appendLine("Content-Type: application/json")
                appendLine()
                appendLine("{")
                appendLine("  // TODO: request payload")
                appendLine("}")
            } else if (details.fieldParams.isNotEmpty() || details.hasFieldMap) {
                appendLine("# TODO: add x-www-form-urlencoded body fields")
            } else if (details.partParams.isNotEmpty() || details.hasPartMap) {
                appendLine("# TODO: add multipart form-data body parts")
            }
            appendLine("```")
            appendLine()
            appendLine("## Notes")
            appendLine("- Authorization header appears only when required or likely optional from Retrofit annotations.")
            if (details.hasQueryMap || details.hasHeaderMap || details.hasFieldMap || details.hasPartMap) {
                appendLine("- This endpoint includes one or more map-based params; expand placeholders as needed.")
            }
            if (details.hasDynamicUrl) {
                appendLine("- `@Url` overrides the path/base URL at runtime; provide `{{full_url}}`.")
            }
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

    /**
     * Renders parameter names as markdown code spans.
     */
    private fun renderNames(names: List<String>): String {
        if (names.isEmpty()) return "None"
        return names.joinToString(", ") { "`$it`" }
    }

    /**
     * Builds a draft request URL with query placeholders.
     */
    private fun buildRequestUrl(baseUrl: String, details: EndpointDocDetails): String {
        if (details.hasDynamicUrl) return "{{full_url}}"

        val queryParts = mutableListOf<String>()
        details.queryParams.forEach { queryName ->
            queryParts += "$queryName={{${toPlaceholder(queryName)}}}"
        }
        if (details.hasQueryMap) {
            queryParts += "{{query_key}}={{query_value}}"
        }
        if (queryParts.isEmpty()) return baseUrl
        return "$baseUrl?${queryParts.joinToString("&")}"
    }

    /**
     * Normalizes arbitrary names into HTTP Client placeholder tokens.
     */
    private fun toPlaceholder(name: String): String {
        return name
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .lowercase()
            .ifBlank { "value" }
    }
}
