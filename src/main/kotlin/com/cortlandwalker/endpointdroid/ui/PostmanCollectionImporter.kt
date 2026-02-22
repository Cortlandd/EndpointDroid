package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.util.Locale

/**
 * Parses Postman collection JSON into endpoint rows and details content.
 */
internal object PostmanCollectionImporter {

    private val mapper = ObjectMapper()

    /**
     * Imports endpoints from Postman collection JSON text.
     */
    fun importFromJson(fileName: String, jsonText: String): ImportResult {
        val root = mapper.readTree(jsonText)
        val collectionName = root.path("info").path("name").asText()
            .ifBlank { fileName.substringBeforeLast('.') }
            .ifBlank { "Collection" }

        val importer = ImportAccumulator(collectionName)
        val items = root.path("item")
        if (items.isArray) {
            items.forEach { item -> importer.visitItem(item, emptyList()) }
        }

        return ImportResult(
            collectionName = collectionName,
            imported = importer.importedEndpoints.sortedWith(
                compareBy({ it.endpoint.serviceFqn }, { it.endpoint.path }, { it.endpoint.functionName })
            )
        )
    }

    internal data class ImportResult(
        val collectionName: String,
        val imported: List<ImportedEndpoint>
    )

    internal data class ImportedEndpoint(
        val endpoint: Endpoint,
        val details: EndpointDocDetails
    )

    /**
     * Collects imported endpoints while walking folder/request trees.
     */
    private class ImportAccumulator(collectionName: String) {
        private val servicePrefix = "postman.${sanitizeName(collectionName)}"
        private val usedFunctionNames = linkedSetOf<String>()
        val importedEndpoints = mutableListOf<ImportedEndpoint>()

        fun visitItem(itemNode: JsonNode, folderStack: List<String>) {
            val nestedItems = itemNode.path("item")
            if (nestedItems.isArray && nestedItems.size() > 0) {
                val folderName = itemNode.path("name").asText().ifBlank { "Folder" }
                val nextFolderStack = folderStack + folderName
                nestedItems.forEach { child -> visitItem(child, nextFolderStack) }
                return
            }

            val request = itemNode.path("request")
            if (request.isMissingNode || request.isNull) return

            val requestName = itemNode.path("name").asText().ifBlank { "request" }
            val method = request.path("method").asText("GET")
                .uppercase(Locale.US)
                .ifBlank { "GET" }
            val urlInfo = parseUrl(request.path("url"))
            val functionName = uniqueFunctionName(folderStack, requestName)
            val serviceFqn = serviceFqnFor(folderStack)

            val requestBody = request.path("body")
            val requestBodyRaw = requestBody.path("raw").asText().takeIf { it.isNotBlank() }
            val responseBodyRaw = itemNode.path("response")
                .firstOrNull { resp -> !resp.path("body").asText().isNullOrBlank() }
                ?.path("body")
                ?.asText()
                ?.takeIf { it.isNotBlank() }

            val endpoint = Endpoint(
                httpMethod = method,
                path = urlInfo.path,
                serviceFqn = serviceFqn,
                functionName = functionName,
                requestType = if (requestBodyRaw != null) "RequestBody" else null,
                responseType = null,
                baseUrl = urlInfo.baseUrl
            )

            val queryDetails = urlInfo.queryParams.map { query ->
                EndpointDocDetails.QueryParamDetails(
                    name = query.name,
                    type = "?",
                    required = !query.optional,
                    defaultValue = query.defaultValue
                )
            }
            val headers = parseHeaders(request.path("header"))
            val staticHeaders = headers.map { "${it.name}: ${it.value}" }
            val pathParams = collectPathParams(endpoint.path)
            val authRequired = headers.any { it.name.equals("Authorization", ignoreCase = true) }
            val authRequirement = if (authRequired) {
                EndpointDocDetails.AuthRequirement.REQUIRED
            } else {
                EndpointDocDetails.AuthRequirement.NONE
            }

            importedEndpoints += ImportedEndpoint(
                endpoint = endpoint,
                details = EndpointDocDetails.empty().copy(
                    providerLabel = "Postman",
                    pathParams = pathParams,
                    queryParams = queryDetails.map { it.name },
                    queryParamDetails = queryDetails,
                    headerParams = headers.map { it.name },
                    staticHeaders = staticHeaders,
                    hasBody = requestBodyRaw != null,
                    requestExampleJson = requestBodyRaw?.takeIf(::looksLikeJson),
                    responseExampleJson = responseBodyRaw?.takeIf(::looksLikeJson),
                    authRequirement = authRequirement
                )
            )
        }

        private fun serviceFqnFor(folderStack: List<String>): String {
            if (folderStack.isEmpty()) return servicePrefix
            val suffix = folderStack.joinToString(".") { sanitizeName(it) }
            return "$servicePrefix.$suffix"
        }

        private fun uniqueFunctionName(folderStack: List<String>, requestName: String): String {
            val folderPrefix = folderStack.joinToString("_") { sanitizeName(it) }
            val base = listOf(folderPrefix, sanitizeName(requestName))
                .filter { it.isNotBlank() }
                .joinToString("_")
                .ifBlank { "request" }

            var candidate = base
            var index = 2
            while (!usedFunctionNames.add(candidate)) {
                candidate = "${base}_$index"
                index += 1
            }
            return candidate
        }
    }

    private data class UrlInfo(
        val baseUrl: String?,
        val path: String,
        val queryParams: List<QueryParam>
    )

    private data class QueryParam(
        val name: String,
        val defaultValue: String?,
        val optional: Boolean
    )

    private data class HeaderEntry(
        val name: String,
        val value: String
    )

    /**
     * Parses Postman URL values from either raw string or structured object form.
     */
    private fun parseUrl(urlNode: JsonNode): UrlInfo {
        if (urlNode.isMissingNode || urlNode.isNull) {
            return UrlInfo(baseUrl = null, path = "/", queryParams = emptyList())
        }

        if (urlNode.isTextual) {
            return parseRawUrl(urlNode.asText())
        }

        val raw = urlNode.path("raw").asText()
        if (raw.isNotBlank()) {
            return parseRawUrl(raw)
        }

        val protocol = urlNode.path("protocol").asText().ifBlank { "https" }
        val host = when {
            urlNode.path("host").isArray -> urlNode.path("host").joinToString(".") { it.asText() }
            else -> urlNode.path("host").asText()
        }.trim()
        val pathSegments = when {
            urlNode.path("path").isArray -> urlNode.path("path").map { normalizePathSegment(it.asText()) }
            else -> listOf(urlNode.path("path").asText()).filter { it.isNotBlank() }.map(::normalizePathSegment)
        }
        val queryParams = parseQueryArray(urlNode.path("query"))

        val path = "/" + pathSegments.filter { it.isNotBlank() }.joinToString("/")
        val normalizedPath = if (path == "/") "/" else path.replace("//", "/")
        val baseUrl = if (host.isBlank() || host.contains("{{")) null else "$protocol://$host".trimEnd('/')
        return UrlInfo(baseUrl = baseUrl, path = normalizedPath, queryParams = queryParams)
    }

    private fun parseRawUrl(rawUrl: String): UrlInfo {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return UrlInfo(baseUrl = null, path = "/", queryParams = emptyList())

        // Parse absolute URLs when possible; otherwise keep path-ish content.
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (uri != null && !uri.scheme.isNullOrBlank() && !uri.authority.isNullOrBlank()) {
            val baseUrl = "${uri.scheme}://${uri.authority}".trimEnd('/')
            val path = (uri.rawPath ?: "/").ifBlank { "/" }
            val params = parseQueryText(uri.rawQuery)
            return UrlInfo(baseUrl = baseUrl, path = ensureLeadingSlash(path), queryParams = params)
        }

        val querySeparator = trimmed.indexOf('?')
        val rawPath = if (querySeparator >= 0) trimmed.substring(0, querySeparator) else trimmed
        val queryText = if (querySeparator >= 0) trimmed.substring(querySeparator + 1) else null
        return UrlInfo(
            baseUrl = null,
            path = ensureLeadingSlash(normalizePathSegment(rawPath)),
            queryParams = parseQueryText(queryText)
        )
    }

    private fun parseQueryArray(queryNode: JsonNode): List<QueryParam> {
        if (!queryNode.isArray) return emptyList()
        return queryNode.mapNotNull { node ->
            val name = node.path("key").asText().trim()
            if (name.isBlank()) return@mapNotNull null
            QueryParam(
                name = name,
                defaultValue = node.path("value").asText().takeIf { it.isNotBlank() },
                optional = node.path("disabled").asBoolean(false)
            )
        }
    }

    private fun parseQueryText(queryText: String?): List<QueryParam> {
        if (queryText.isNullOrBlank()) return emptyList()
        return queryText.split('&')
            .mapNotNull { pair ->
                val token = pair.trim()
                if (token.isBlank()) return@mapNotNull null
                val delimiter = token.indexOf('=')
                val name = if (delimiter >= 0) token.substring(0, delimiter) else token
                val value = if (delimiter >= 0) token.substring(delimiter + 1) else null
                QueryParam(
                    name = name,
                    defaultValue = value?.takeIf { it.isNotBlank() },
                    optional = false
                )
            }
    }

    private fun parseHeaders(headersNode: JsonNode): List<HeaderEntry> {
        if (!headersNode.isArray) return emptyList()
        return headersNode.mapNotNull { header ->
            if (header.path("disabled").asBoolean(false)) return@mapNotNull null
            val name = header.path("key").asText().trim()
            if (name.isBlank()) return@mapNotNull null
            val value = header.path("value").asText().trim().ifBlank { "(set)" }
            HeaderEntry(name, value)
        }
    }

    private fun collectPathParams(path: String): List<String> {
        return Regex("""\{([^}/]+)}""")
            .findAll(path)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    private fun ensureLeadingSlash(path: String): String {
        if (path.isBlank()) return "/"
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun normalizePathSegment(segment: String): String {
        val trimmed = segment.trim().trim('/')
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith(":")) return "{${trimmed.removePrefix(":")}}"
        val variableMatch = Regex("""\{\{([^}]+)}}""").matchEntire(trimmed)
        if (variableMatch != null) {
            return "{${variableMatch.groupValues[1].trim()}}"
        }
        return trimmed
    }

    private fun sanitizeName(raw: String): String {
        return raw
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "name" }
    }
}
