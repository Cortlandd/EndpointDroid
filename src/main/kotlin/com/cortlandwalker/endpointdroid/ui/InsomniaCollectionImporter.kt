package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.util.Locale

/**
 * Parses Insomnia export JSON into endpoint rows and details content.
 */
internal object InsomniaCollectionImporter {

    private val mapper = ObjectMapper()
    private val hostTemplatePrefix = Regex("""^\{\{\s*[^}]+\s*}}\s*""")
    private val templateTokenRegex = Regex("""\{\{\s*([^}]+)\s*}}""")

    /**
     * Imports endpoints from Insomnia export JSON.
     */
    fun importFromJson(fileName: String, jsonText: String): ImportResult {
        val root = mapper.readTree(jsonText)
        val resources = root.path("resources")
        if (!resources.isArray) {
            return ImportResult(fileName.substringBeforeLast('.').ifBlank { "Insomnia" }, emptyList())
        }

        val workspacesById = linkedMapOf<String, String>()
        val groupsById = linkedMapOf<String, GroupNode>()
        val requests = mutableListOf<JsonNode>()

        resources.forEach { resource ->
            val id = resource.path("_id").asText().trim()
            val type = resource.path("_type").asText().trim()
            if (id.isBlank() || type.isBlank()) return@forEach

            when (type) {
                "workspace" -> {
                    workspacesById[id] = resource.path("name").asText().ifBlank { "Workspace" }
                }
                "request_group" -> {
                    groupsById[id] = GroupNode(
                        id = id,
                        parentId = resource.path("parentId").asText().trim(),
                        name = resource.path("name").asText().ifBlank { "Group" }
                    )
                }
                "request" -> {
                    requests.add(resource)
                }
            }
        }

        val fallbackName = fileName.substringBeforeLast('.').ifBlank { "Insomnia" }
        val workspaceName = workspacesById.values.firstOrNull().orEmpty().ifBlank { fallbackName }
        val accumulator = ImportAccumulator(
            fallbackWorkspaceName = workspaceName,
            workspacesById = workspacesById,
            groupsById = groupsById
        )
        requests.forEach { request -> accumulator.visitRequest(request) }

        return ImportResult(
            workspaceName = workspaceName,
            imported = accumulator.importedEndpoints.sortedWith(
                compareBy({ it.endpoint.serviceFqn }, { it.endpoint.path }, { it.endpoint.functionName })
            )
        )
    }

    internal data class ImportResult(
        val workspaceName: String,
        val imported: List<ImportedEndpoint>
    )

    internal data class ImportedEndpoint(
        val endpoint: Endpoint,
        val details: EndpointDocDetails
    )

    private data class GroupNode(
        val id: String,
        val parentId: String,
        val name: String
    )

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
     * Walks requests and builds Endpoint + details models.
     */
    private class ImportAccumulator(
        private val fallbackWorkspaceName: String,
        private val workspacesById: Map<String, String>,
        private val groupsById: Map<String, GroupNode>
    ) {
        private val usedFunctionNames = linkedSetOf<String>()
        val importedEndpoints = mutableListOf<ImportedEndpoint>()

        fun visitRequest(request: JsonNode) {
            val requestName = request.path("name").asText().ifBlank { "request" }
            val method = request.path("method").asText("GET")
                .uppercase(Locale.US)
                .ifBlank { "GET" }
            val urlInfo = parseUrl(request.path("url").asText())
            val parentChain = resolveParentChain(request.path("parentId").asText().trim())
            val workspaceName = parentChain.workspaceName.ifBlank { fallbackWorkspaceName }
            val folderStack = parentChain.groupNames
            val functionName = uniqueFunctionName(folderStack, requestName)
            val serviceFqn = buildServiceFqn(workspaceName, folderStack)

            val parameters = parseQueryParams(request.path("parameters"))
            val queryMap = linkedMapOf<String, QueryParam>()
            urlInfo.queryParams.forEach { queryMap.putIfAbsent(it.name, it) }
            parameters.forEach { queryMap[it.name] = it }
            val queryDetails = queryMap.values.toList()

            val headers = parseHeaders(request.path("headers"))
            val staticHeaders = headers.map { "${it.name}: ${it.value}" }
            val authType = request.path("authentication").path("type").asText()
            val authRequired = authType.isNotBlank() && authType.lowercase(Locale.US) !in setOf("none", "noauth")
            val hasAuthorizationHeader = headers.any { it.name.equals("Authorization", ignoreCase = true) }
            val authRequirement = when {
                hasAuthorizationHeader || authRequired -> EndpointDocDetails.AuthRequirement.REQUIRED
                else -> EndpointDocDetails.AuthRequirement.NONE
            }

            val bodyText = request.path("body").path("text").asText().takeIf { it.isNotBlank() }
            val endpoint = Endpoint(
                httpMethod = method,
                path = urlInfo.path,
                serviceFqn = serviceFqn,
                functionName = functionName,
                requestType = if (bodyText != null) "RequestBody" else null,
                responseType = null,
                baseUrl = urlInfo.baseUrl
            )

            importedEndpoints += ImportedEndpoint(
                endpoint = endpoint,
                details = EndpointDocDetails.empty().copy(
                    providerLabel = "Insomnia",
                    pathParams = collectPathParams(endpoint.path),
                    queryParams = queryDetails.map { it.name },
                    queryParamDetails = queryDetails.map { query ->
                        EndpointDocDetails.QueryParamDetails(
                            name = query.name,
                            type = "?",
                            required = !query.optional,
                            defaultValue = query.defaultValue
                        )
                    },
                    headerParams = headers.map { it.name },
                    staticHeaders = staticHeaders,
                    hasBody = bodyText != null,
                    requestExampleJson = bodyText?.takeIf(::looksLikeJson),
                    authRequirement = authRequirement
                )
            )
        }

        private fun resolveParentChain(parentId: String): ParentChain {
            if (parentId.isBlank()) return ParentChain(workspaceName = fallbackWorkspaceName, groupNames = emptyList())

            var cursor = parentId
            val groups = mutableListOf<String>()
            var workspaceName = fallbackWorkspaceName
            val seen = hashSetOf<String>()

            while (cursor.isNotBlank() && seen.add(cursor)) {
                val workspace = workspacesById[cursor]
                if (workspace != null) {
                    workspaceName = workspace
                    break
                }

                val group = groupsById[cursor] ?: break
                groups += group.name
                cursor = group.parentId
            }

            return ParentChain(workspaceName = workspaceName, groupNames = groups.reversed())
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

        private fun buildServiceFqn(workspaceName: String, folderStack: List<String>): String {
            val parts = mutableListOf("insomnia", sanitizeName(workspaceName))
            folderStack.forEach { parts += sanitizeName(it) }
            return parts.joinToString(".")
        }
    }

    private data class ParentChain(
        val workspaceName: String,
        val groupNames: List<String>
    )

    /**
     * Parses a request URL and derives base URL/path/query values.
     */
    private fun parseUrl(rawUrl: String): UrlInfo {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return UrlInfo(baseUrl = null, path = "/", queryParams = emptyList())

        if (!trimmed.contains("{{")) {
            val uri = runCatching { URI(trimmed) }.getOrNull()
            if (uri != null && !uri.scheme.isNullOrBlank() && !uri.authority.isNullOrBlank()) {
                val baseUrl = "${uri.scheme}://${uri.authority}".trimEnd('/')
                val path = ensureLeadingSlash(uri.rawPath.orEmpty().ifBlank { "/" })
                return UrlInfo(baseUrl = baseUrl, path = normalizePath(path), queryParams = parseQueryText(uri.rawQuery))
            }
        }

        val withoutTemplateHost = hostTemplatePrefix.replace(trimmed, "").trim()
        val querySeparator = withoutTemplateHost.indexOf('?')
        val rawPath = if (querySeparator >= 0) {
            withoutTemplateHost.substring(0, querySeparator)
        } else {
            withoutTemplateHost
        }
        val rawQuery = if (querySeparator >= 0) withoutTemplateHost.substring(querySeparator + 1) else null
        val normalizedPath = normalizePath(ensureLeadingSlash(rewriteTemplateTokens(rawPath)))
        return UrlInfo(baseUrl = null, path = normalizedPath, queryParams = parseQueryText(rawQuery))
    }

    private fun parseQueryParams(parametersNode: JsonNode): List<QueryParam> {
        if (!parametersNode.isArray) return emptyList()
        return parametersNode.mapNotNull { parameter ->
            if (parameter.path("disabled").asBoolean(false)) return@mapNotNull null
            val name = parameter.path("name").asText().trim()
            if (name.isBlank()) return@mapNotNull null
            QueryParam(
                name = name,
                defaultValue = parameter.path("value").asText().takeIf { it.isNotBlank() },
                optional = false
            )
        }
    }

    private fun parseHeaders(headersNode: JsonNode): List<HeaderEntry> {
        if (!headersNode.isArray) return emptyList()
        return headersNode.mapNotNull { header ->
            if (header.path("disabled").asBoolean(false)) return@mapNotNull null
            val name = header.path("name").asText().ifBlank { header.path("key").asText() }.trim()
            if (name.isBlank()) return@mapNotNull null
            val value = header.path("value").asText().trim().ifBlank { "(set)" }
            HeaderEntry(name = name, value = value)
        }
    }

    private fun parseQueryText(rawQuery: String?): List<QueryParam> {
        if (rawQuery.isNullOrBlank()) return emptyList()
        return rawQuery.split('&')
            .mapNotNull { token ->
                val pair = token.trim()
                if (pair.isBlank()) return@mapNotNull null
                val delimiter = pair.indexOf('=')
                val name = if (delimiter >= 0) pair.substring(0, delimiter) else pair
                val value = if (delimiter >= 0) pair.substring(delimiter + 1) else null
                QueryParam(name = name, defaultValue = value?.takeIf { it.isNotBlank() }, optional = false)
            }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank() || path == "/") return "/"
        val segments = path.split('/')
            .filter { it.isNotBlank() }
            .map(::normalizePathSegment)
            .filter { it.isNotBlank() }
        return "/" + segments.joinToString("/")
    }

    private fun normalizePathSegment(segment: String): String {
        val trimmed = segment.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith(":")) return "{${trimmed.removePrefix(":")}}"
        return rewriteTemplateTokens(trimmed)
    }

    private fun rewriteTemplateTokens(text: String): String {
        return templateTokenRegex.replace(text) { match ->
            val raw = match.groupValues[1].trim()
            val key = raw.substringAfterLast('.').ifBlank { "value" }
            "{${sanitizeName(key)}}"
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

    private fun ensureLeadingSlash(path: String): String {
        if (path.isBlank()) return "/"
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    private fun sanitizeName(raw: String): String {
        return raw
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "name" }
    }
}
