package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import java.net.URI

/**
 * Builds a compact endpoint details document optimized for fast scanning.
 *
 * Formatting decisions in this renderer intentionally minimize noisy output:
 * optional sections are omitted when empty and the header carries key badges.
 */
internal object MarkdownDocRenderer {
    /**
     * Renders endpoint details as markdown text for the right-side panel.
     */
    fun render(ep: Endpoint, details: EndpointDocDetails = EndpointDocDetails.empty()): String {
        val method = ep.httpMethod.uppercase()
        val pathForDisplay = normalizeDisplayPath(ep.path)
        val pathParams = collectPathParams(ep.path, details.pathParams)
        val queryParams = details.queryParams.distinct()

        val confidence = computeConfidence(ep.baseUrl, details.baseUrlFromConfig)
        val authHint = authHint(details.authRequirement)
        val paramsBadge = paramsBadge(pathParams.size, queryParams.size, details.hasQueryMap)

        val resolvedUrl = resolveUrl(ep.baseUrl, ep.path)
        val baseUrlValue = ep.baseUrl?.trimEnd('/') ?: "{{host}}"
        val baseUrlSource = when {
            ep.baseUrl == null -> "unresolved"
            details.baseUrlFromConfig -> "config"
            else -> "inferred"
        }

        val serviceSimpleName = ep.serviceFqn.substringAfterLast('.')
        val functionLink = EndpointDocLinks.functionUrl(ep.serviceFqn, ep.functionName)
        val sourceLabel = if (details.sourceFile != null && details.sourceLine != null) {
            "${details.sourceFile}:${details.sourceLine}"
        } else {
            "${ep.serviceFqn}#${ep.functionName}"
        }

        return buildString {
            appendLine(buildHeaderLine(method, pathForDisplay, authHint, paramsBadge, confidence))
            appendLine()
            appendLine("Resolved URL: $resolvedUrl")
            appendLine("Base URL: $baseUrlValue  ($baseUrlSource)")
            appendLine("Source: [$sourceLabel (open)]($functionLink)")
            appendLine()

            appendLine("Types")
            appendLine("- Request: ${renderType(ep.requestType)}")
            appendLine("- Response: ${renderType(ep.responseType, fallback = "Unknown")}")

            if (pathParams.isNotEmpty()) {
                appendLine()
                appendLine("Path Parameters")
                pathParams.forEach { name ->
                    appendLine("- `$name`")
                }
            }

            if (queryParams.isNotEmpty() || details.hasQueryMap) {
                appendLine()
                appendLine("Query Parameters")
                if (queryParams.isNotEmpty()) {
                    appendLine("| name | type | required | default |")
                    appendLine("|------|------|----------|---------|")
                    queryParams.forEach { name ->
                        // Query metadata shape is not fully modeled yet, so unknown columns stay explicit.
                        appendLine("| $name | ? | ? | ? |")
                    }
                }
                if (details.hasQueryMap) {
                    appendLine("- Dynamic entries via `@QueryMap`")
                }
            }

            if (details.headerParams.isNotEmpty() || details.hasHeaderMap) {
                appendLine()
                appendLine("Header Parameters")
                details.headerParams.distinct().forEach { name ->
                    appendLine("- `$name`")
                }
                if (details.hasHeaderMap) {
                    appendLine("- Dynamic entries via `@HeaderMap`")
                }
            }

            if (details.fieldParams.isNotEmpty() || details.hasFieldMap) {
                appendLine()
                appendLine("Form Fields")
                details.fieldParams.distinct().forEach { name ->
                    appendLine("- `$name`")
                }
                if (details.hasFieldMap) {
                    appendLine("- Dynamic entries via `@FieldMap`")
                }
            }

            if (details.partParams.isNotEmpty() || details.hasPartMap) {
                appendLine()
                appendLine("Multipart Parts")
                details.partParams.distinct().forEach { name ->
                    appendLine("- `$name`")
                }
                if (details.hasPartMap) {
                    appendLine("- Dynamic entries via `@PartMap`")
                }
            }

            appendLine()
            appendLine("HTTP Client (.http)")
            appendLine("```http")
            appendLine("### $serviceSimpleName.${ep.functionName}")
            appendLine("$method ${buildHttpClientUrl(ep.path, queryParams)}")
            if (details.hasQueryMap) {
                appendLine("# Add @QueryMap entries to the URL as needed.")
            }
            appendLine("Accept: application/json")
            if (details.authRequirement == EndpointDocDetails.AuthRequirement.REQUIRED) {
                appendLine("Authorization: Bearer {{token}}")
            }
            appendLine("```")

            appendLine()
            appendLine("Notes")
            appendLine("- Authorization header is included only when required (from Retrofit annotations/headers).")
            if (ep.baseUrl == null) {
                appendLine("- `{{host}}` is unresolved; define it in endpointdroid.yaml or http-client.env.json.")
            } else {
                appendLine("- `.http` requests use `{{host}}`; define it in http-client.env.json.")
            }
        }
    }

    /**
     * Builds the compact one-line endpoint header with status badges.
     */
    private fun buildHeaderLine(
        method: String,
        pathForDisplay: String,
        authHint: String,
        paramsBadge: String?,
        confidence: String
    ): String {
        val badges = mutableListOf("[Retrofit]", "[Auth: $authHint]")
        if (paramsBadge != null) {
            badges += "[Params: $paramsBadge]"
        }
        badges += "[Confidence: $confidence]"
        return "$method $pathForDisplay    ${badges.joinToString(" ")}"
    }

    /**
     * Renders a type as a declaration link when available.
     */
    private fun renderType(type: String?, fallback: String = "None"): String {
        if (type == null) return fallback
        val link = EndpointDocLinks.typeUrl(type)
        return "[`$type`]($link)"
    }

    /**
     * Produces an auth badge label from inferred requirement state.
     */
    private fun authHint(requirement: EndpointDocDetails.AuthRequirement): String {
        return when (requirement) {
            EndpointDocDetails.AuthRequirement.REQUIRED -> "Required"
            EndpointDocDetails.AuthRequirement.OPTIONAL -> "Optional"
            EndpointDocDetails.AuthRequirement.NONE -> "None"
        }
    }

    /**
     * Computes confidence using available base URL signal quality.
     */
    private fun computeConfidence(baseUrl: String?, fromConfig: Boolean): String {
        val normalized = baseUrl?.trim()
        if (normalized.isNullOrBlank()) return "Low"
        return if (fromConfig) "High" else if (looksAbsoluteUrl(normalized)) "Medium" else "Low"
    }

    /**
     * Builds optional parameter summary badge text.
     */
    private fun paramsBadge(pathCount: Int, queryCount: Int, hasQueryMap: Boolean): String? {
        val parts = mutableListOf<String>()
        if (pathCount > 0) parts += "path($pathCount)"
        if (queryCount > 0) {
            parts += "query($queryCount)"
        } else if (hasQueryMap) {
            // QueryMap means query params exist but names are runtime-defined.
            parts += "query(*)"
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /**
     * Returns a display path, preserving explicit absolute URLs when provided.
     */
    private fun normalizeDisplayPath(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        return if (path.startsWith("/")) path else "/$path"
    }

    /**
     * Resolves URL for display by combining base URL and endpoint path safely.
     */
    private fun resolveUrl(baseUrl: String?, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        val host = (baseUrl ?: "{{host}}").trimEnd('/')
        val suffix = normalizeDisplayPath(path)
        return "$host$suffix"
    }

    /**
     * Always builds env-friendly HTTP Client request URLs using `{{host}}`.
     */
    private fun buildHttpClientUrl(path: String, queryParams: List<String>): String {
        val requestPath = renderRequestPathPlaceholders(normalizeRequestPath(path))
        if (queryParams.isEmpty()) return "{{host}}$requestPath"

        // Keep placeholders deterministic and explicit for generated .http snippets.
        val query = queryParams.joinToString("&") { name ->
            "$name={{${toPlaceholder(name)}}}"
        }
        return "{{host}}$requestPath?$query"
    }

    /**
     * Chooses request path component for HTTP snippets even when source path is absolute.
     */
    private fun normalizeRequestPath(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            val uri = runCatching { URI(path) }.getOrNull()
            val rawPath = uri?.rawPath?.takeIf { it.isNotBlank() } ?: "/"
            return if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        }
        return if (path.startsWith("/")) path else "/$path"
    }

    /**
     * Converts Retrofit-style path params (`{id}`) into HTTP Client placeholders (`{{id}}`).
     */
    private fun renderRequestPathPlaceholders(path: String): String {
        return Regex("\\{([^}/]+)\\}").replace(path) { match ->
            "{{${toPlaceholder(match.groupValues[1])}}}"
        }
    }

    /**
     * Collects path params from both Retrofit metadata and `{param}` path tokens.
     */
    private fun collectPathParams(path: String, declared: List<String>): List<String> {
        val merged = LinkedHashSet<String>()
        declared.forEach { merged += it }
        Regex("\\{([^}/]+)\\}")
            .findAll(path)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .forEach { merged += it }
        return merged.toList()
    }

    /**
     * Checks whether a string is an absolute HTTP(S) URL.
     */
    private fun looksAbsoluteUrl(value: String): Boolean {
        return value.startsWith("http://") || value.startsWith("https://")
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
