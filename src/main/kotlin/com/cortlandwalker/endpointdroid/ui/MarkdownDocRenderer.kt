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
    private const val MAX_JSON_PREVIEW_LINES = 40
    private const val MAX_JSON_PREVIEW_CHARS = 3500
    private const val DEFAULT_API_ERROR_JSON = """{
  "code": "INVALID_CREDENTIALS",
  "message": "string"
}"""

    /**
     * Renders endpoint details as markdown text for the right-side panel.
     */
    fun render(ep: Endpoint, details: EndpointDocDetails = EndpointDocDetails.empty()): String {
        val method = ep.httpMethod.uppercase()
        val pathForDisplay = normalizeDisplayPath(ep.path)
        val pathParams = collectPathParams(ep.path, details.pathParams)
        val queryDetails = resolveQueryDetails(details)
        val queryParams = queryDetails.map { it.name }
        val headerRows = buildHeaderRows(details)

        val authHint = authHint(details.authRequirement)
        val paramsBadge = paramsBadge(pathParams.size, queryParams.size)
        val providerLabel = details.providerLabel
            .takeUnless { it.isBlank() || it == "Unknown" }
            ?: inferProviderLabel(ep)

        val resolvedUrl = resolveUrl(ep.baseUrl, ep.path)
        val baseUrlValue = ep.baseUrl?.trimEnd('/') ?: "{{host}}"
        val baseUrlSource = when {
            ep.baseUrl == null -> "unresolved"
            details.baseUrlFromConfig -> "config"
            else -> "inferred"
        }

        val serviceSimpleName = ep.serviceFqn.substringAfterLast('.')
        val serviceLink = EndpointDocLinks.serviceUrl(ep.serviceFqn)
        val functionLink = EndpointDocLinks.functionUrl(ep.serviceFqn, ep.functionName)
        val sourceLabel = if (details.sourceFile != null && details.sourceLine != null) {
            "${details.sourceFile}:${details.sourceLine}"
        } else {
            "${ep.serviceFqn}#${ep.functionName}"
        }
        val requestSchemaPreview = previewJson(details.requestSchemaJson)
        val requestExamplePreview = previewJson(details.requestExampleJson)
        val successResponsePreview = previewJson(details.responseExampleJson ?: details.responseSchemaJson ?: "{}")
        val errorResponsePreview = previewJson(DEFAULT_API_ERROR_JSON)

        return buildString {
            appendLine(buildHeaderLine(method, pathForDisplay, providerLabel, authHint, paramsBadge))
            appendLine()
            appendLine("- Resolved URL: `$resolvedUrl`")
            appendLine("- Base URL: `$baseUrlValue` ($baseUrlSource)")
            appendLine("- Service: [`$serviceSimpleName`]($serviceLink)")
            if (details.sourceFile != null && details.sourceLine != null) {
                appendLine("- Source: [$sourceLabel (open)]($functionLink)")
            } else {
                appendLine("- Source: $sourceLabel")
            }
            appendLine()
            appendLine(sectionTitle("Usage"))
            if (details.usageLocations.isEmpty()) {
                appendLine("- No usages found in project scope.")
            } else {
                details.usageLocations.forEach { usage ->
                    val usageLabel = buildUsageLabel(usage)
                    val usageLink = EndpointDocLinks.usageUrl(usage.filePath, usage.offset)
                    appendLine("- [$usageLabel]($usageLink)")
                }
                if (details.usageLocationsTruncated) {
                    appendLine("- _Showing first ${details.usageLocations.size} usages._")
                }
            }
            appendLine()

            appendLine(sectionTitle("Types"))
            appendLine("- Request: ${renderType(ep.requestType)}")
            appendLine("- Response: ${renderType(ep.responseType, fallback = "Unknown")}")

            if (pathParams.isNotEmpty()) {
                appendLine()
                appendLine(sectionTitle("Path Parameters"))
                pathParams.forEach { name ->
                    appendLine("- `$name`")
                }
            }

            if (queryParams.isNotEmpty() || details.hasQueryMap) {
                appendLine()
                appendLine(sectionTitle("Query Parameters"))
                if (queryParams.isNotEmpty()) {
                    appendLine()
                    appendLine(renderMarkdownTable(
                        headers = listOf("name", "type", "required", "default"),
                        rows = queryDetails.map { query ->
                            listOf(
                                query.name,
                                query.type.ifBlank { "?" },
                                if (query.required) "yes" else "no",
                                query.defaultValue ?: "-"
                            )
                        }
                    ))
                    appendLine()
                }
                if (details.hasQueryMap) {
                    appendLine()
                    appendLine("- Dynamic entries via `@QueryMap`")
                }
            }

            if (headerRows.isNotEmpty() || details.hasHeaderMap) {
                appendLine()
                appendLine(sectionTitle("Header Parameters"))
                if (headerRows.isNotEmpty()) {
                    appendLine()
                    appendLine(renderMarkdownTable(headers = listOf("name", "source", "value"), rows = headerRows))
                    appendLine()
                }
                if (details.hasHeaderMap) {
                    appendLine()
                    appendLine("- Dynamic entries via `@HeaderMap`")
                }
            }

            if (details.fieldParams.isNotEmpty() || details.hasFieldMap) {
                appendLine()
                appendLine(sectionTitle("Form Fields"))
                details.fieldParams.distinct().forEach { name ->
                    appendLine("- `$name`")
                }
                if (details.hasFieldMap) {
                    appendLine("- Dynamic entries via `@FieldMap`")
                }
            }

            if (details.partParams.isNotEmpty() || details.hasPartMap) {
                appendLine()
                appendLine(sectionTitle("Multipart Parts"))
                details.partParams.distinct().forEach { name ->
                    appendLine("- `$name`")
                }
                if (details.hasPartMap) {
                    appendLine("- Dynamic entries via `@PartMap`")
                }
            }

            appendLine()
            appendLine(sectionTitle("HTTP Client (.http)"))
            appendLine()
            appendLine(
                fencedCodeBlock(
                    language = "http",
                    body = buildString {
                        appendLine("### $serviceSimpleName.${ep.functionName}")
                        appendLine("$method ${buildHttpClientUrl(ep.path, queryParams)}")
                        if (details.hasQueryMap) {
                            appendLine("# Add @QueryMap entries to the URL as needed.")
                        }
                        appendLine("Accept: application/json")
                        if (details.authRequirement == EndpointDocDetails.AuthRequirement.REQUIRED) {
                            appendLine("Authorization: Bearer {{token}}")
                        }
                    }
                )
            )
            appendLine()

            if (details.hasBody || ep.requestType != null) {
                appendLine()
                appendLine(sectionTitle("Request body"))
                appendLine("- Type: ${renderType(ep.requestType)}")
                appendLine("- Content-Type: application/json")
                details.requestSchemaJson?.let {
                    appendLine()
                    appendLine("Schema (from model):")
                    appendLine()
                    appendLine(fencedCodeBlock("json", requestSchemaPreview.content))
                    if (requestSchemaPreview.truncated) {
                        appendLine("_Schema preview truncated for readability._")
                    }
                }
                details.requestExampleJson?.let {
                    appendLine()
                    appendLine("Example:")
                    appendLine()
                    appendLine(fencedCodeBlock("json", requestExamplePreview.content))
                    if (requestExamplePreview.truncated) {
                        appendLine("_Example preview truncated for readability._")
                    }
                }
            }

            appendLine()
            appendLine(sectionTitle("Response"))
            appendLine("- Type: ${renderType(ep.responseType, fallback = "Unknown")}")
            appendLine()
            appendLine(sectionTitle("Success"))
            appendLine("- 200 OK")
            appendLine()
            appendLine(fencedCodeBlock("json", successResponsePreview.content))
            if (successResponsePreview.truncated) {
                appendLine("_Response preview truncated for readability._")
            }
            appendLine()
            appendLine(sectionTitle("Error"))
            appendLine("- 400 Bad Request -> ApiError")
            appendLine("- 401 Unauthorized -> ApiError")
            appendLine()
            appendLine(fencedCodeBlock("json", errorResponsePreview.content))

            appendLine()
            appendLine(sectionTitle("Notes"))
            appendLine("- Authorization header is included only when required (from endpoint metadata/headers).")
            if (ep.baseUrl == null) {
                appendLine("- `{{host}}` is unresolved; define it in endpointdroid.yaml or http-client.env.json.")
            } else {
                appendLine("- `.http` requests use `{{host}}`; define it in http-client.env.json.")
            }
            if (successResponsePreview.truncated || requestSchemaPreview.truncated || requestExamplePreview.truncated) {
                appendLine("- Large JSON payloads are shown as previews; open linked source types for full shape.")
            }
        }
    }

    /**
     * Resolves query rows with metadata when available and falls back to name-only rows.
     */
    private fun resolveQueryDetails(details: EndpointDocDetails): List<EndpointDocDetails.QueryParamDetails> {
        if (details.queryParamDetails.isNotEmpty()) {
            return details.queryParamDetails.distinctBy { it.name }
        }
        return details.queryParams.distinct().map { name ->
            EndpointDocDetails.QueryParamDetails(
                name = name,
                type = "?",
                required = true,
                defaultValue = null
            )
        }
    }

    /**
     * Builds header table rows from both dynamic and static Retrofit header declarations.
     */
    private fun buildHeaderRows(details: EndpointDocDetails): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        details.headerParams.distinct().forEach { name ->
            rows += listOf(name, "@Header", "{{${toPlaceholder(name)}}}")
        }
        details.staticHeaders.forEach { headerLine ->
            val name = headerLine.substringBefore(':').trim()
            val value = headerLine.substringAfter(':', "").trim()
            rows += listOf(name.ifBlank { "(header)" }, "@Headers", value.ifBlank { "(set)" })
        }
        return rows
    }

    /**
     * Renders markdown table text for parameter sections.
     */
    private fun renderMarkdownTable(headers: List<String>, rows: List<List<String>>): String {
        val normalizedRows = rows.map { row ->
            headers.indices.map { index ->
                val value = row.getOrNull(index).orEmpty()
                escapeMarkdownCell(value)
            }
        }
        return buildString {
            appendLine("| ${headers.joinToString(" | ") { escapeMarkdownCell(it) }} |")
            appendLine("| ${headers.joinToString(" | ") { "---" }} |")
            normalizedRows.forEach { row ->
                appendLine("| ${row.joinToString(" | ")} |")
            }
        }.trimEnd()
    }

    private fun escapeMarkdownCell(value: String): String {
        return value.replace("|", "\\|")
    }

    /**
     * Builds a bold section title that reliably starts a new markdown block.
     */
    private fun sectionTitle(title: String): String = "**$title**"

    /**
     * Formats a usage entry label for markdown link rendering.
     */
    private fun buildUsageLabel(usage: EndpointDocDetails.UsageLocation): String {
        val context = usage.containerName?.takeIf { it.isNotBlank() }?.let { " - $it()" }.orEmpty()
        return "${usage.displayPath}:${usage.line}$context"
    }

    /**
     * Builds the compact one-line endpoint header with status badges.
     */
    private fun buildHeaderLine(
        method: String,
        pathForDisplay: String,
        providerLabel: String,
        authHint: String,
        paramsBadge: String?
    ): String {
        val badges = mutableListOf("[$providerLabel]", "[Auth: $authHint]")
        if (paramsBadge != null) {
            badges += "[Params: $paramsBadge]"
        }
        return "$method $pathForDisplay    ${badges.joinToString(" ")}"
    }

    /**
     * Uses endpoint naming hints when provider-specific details are unavailable.
     */
    private fun inferProviderLabel(endpoint: Endpoint): String {
        val service = endpoint.serviceFqn.lowercase()
        return if (service.contains("okhttp")) "OkHttp" else "Retrofit"
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
     * Builds optional parameter summary badge text.
     */
    private fun paramsBadge(pathCount: Int, queryCount: Int): String? {
        val parts = mutableListOf<String>()
        if (pathCount > 0) parts += "path($pathCount)"
        if (queryCount > 0) parts += "query($queryCount)"
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
     * Normalizes arbitrary names into HTTP Client placeholder tokens.
     */
    private fun toPlaceholder(name: String): String {
        return name
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .lowercase()
            .ifBlank { "value" }
    }

    /**
     * Truncates large JSON blocks to keep the details panel scannable.
     */
    private fun previewJson(json: String?): JsonPreview {
        val source = json?.trim().takeUnless { it.isNullOrEmpty() } ?: "{}"
        val lines = source.lines()
        val limitedByLines = if (lines.size > MAX_JSON_PREVIEW_LINES) {
            lines.take(MAX_JSON_PREVIEW_LINES).joinToString("\n")
        } else {
            source
        }
        val limitedByChars = if (limitedByLines.length > MAX_JSON_PREVIEW_CHARS) {
            limitedByLines.take(MAX_JSON_PREVIEW_CHARS)
        } else {
            limitedByLines
        }
        val truncated = limitedByChars.length < source.length
        val content = if (truncated) "$limitedByChars\n..." else limitedByChars
        return JsonPreview(content = content, truncated = truncated)
    }

    private data class JsonPreview(
        val content: String,
        val truncated: Boolean
    )

    /**
     * Emits fenced code blocks with consistent spacing so markdown parsers recognize them.
     */
    private fun fencedCodeBlock(language: String, body: String): String {
        val normalizedBody = body.trimEnd()
        return buildString {
            append("```")
            append(language)
            append('\n')
            append(normalizedBody)
            if (!normalizedBody.endsWith('\n')) {
                append('\n')
            }
            append("```")
        }
    }
}
