package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.project.Project
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies user-authored endpoint overrides from a project config file.
 *
 * Supported file names in project root:
 * - `endpointdroid.yaml`
 * - `.endpointdroid.yaml`
 *
 * Supported keys:
 * - `baseUrl` / `base_url`
 * - `defaultEnv` / `default_env`
 * - `environments`
 * - `serviceBaseUrls`
 * - `servicePaths`
 * - `serviceRequestTypes`
 * - `serviceResponseTypes`
 *
 * Map sections support both per-service and per-endpoint keys:
 * - `com.example.api.SotwApi`
 * - `com.example.api.SotwApi#search`
 */
internal object EndpointConfigResolver {

    private const val CONFIG_FILE = "endpointdroid.yaml"
    private const val ALT_CONFIG_FILE = ".endpointdroid.yaml"

    private val cacheByProject = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Applies config overrides to scanned endpoints.
     */
    fun apply(project: Project, endpoints: List<Endpoint>): List<Endpoint> {
        if (endpoints.isEmpty()) return endpoints
        val config = loadConfig(project) ?: return endpoints

        return endpoints.map { endpoint ->
            val endpointKey = "${endpoint.serviceFqn}#${endpoint.functionName}"

            val baseUrlOverride = firstNonNull(
                config.resolveBaseUrl(config.serviceBaseUrls[endpointKey]),
                config.resolveBaseUrl(config.serviceBaseUrls[endpoint.serviceFqn]),
                config.globalBaseUrl
            )

            val pathOverride = firstNonBlank(
                config.servicePaths[endpointKey],
                config.servicePaths[endpoint.serviceFqn]
            )
            val requestTypeOverride = firstNonBlank(
                config.serviceRequestTypes[endpointKey],
                config.serviceRequestTypes[endpoint.serviceFqn]
            )
            val responseTypeOverride = firstNonBlank(
                config.serviceResponseTypes[endpointKey],
                config.serviceResponseTypes[endpoint.serviceFqn]
            )

            val absolutePath = pathOverride?.let(::parseAbsolutePathOverride)
            val normalizedPath = absolutePath?.path
                ?: pathOverride?.let(::normalizePath)
                ?: endpoint.path

            endpoint.copy(
                path = normalizedPath,
                requestType = requestTypeOverride ?: endpoint.requestType,
                responseType = responseTypeOverride ?: endpoint.responseType,
                baseUrl = absolutePath?.baseUrl ?: baseUrlOverride ?: endpoint.baseUrl
            )
        }
    }

    /**
     * Returns the first discovered config path, preferring `endpointdroid.yaml`.
     */
    fun resolveConfigPath(project: Project): Path? {
        val basePath = project.basePath ?: return null
        val root = Path.of(basePath)
        val primary = root.resolve(CONFIG_FILE)
        if (Files.isRegularFile(primary)) return primary

        val alternate = root.resolve(ALT_CONFIG_FILE)
        if (Files.isRegularFile(alternate)) return alternate

        return null
    }

    /**
     * Returns where a new config file should be created.
     */
    fun defaultConfigPath(project: Project): Path? {
        val basePath = project.basePath ?: return null
        return Path.of(basePath, CONFIG_FILE)
    }

    /**
     * Default file content for newly created config files.
     */
    fun templateContent(): String = TEMPLATE

    private fun loadConfig(project: Project): EndpointConfig? {
        val configPath = resolveConfigPath(project) ?: return null
        val projectKey = project.basePath ?: project.locationHash
        val modifiedAt = runCatching { Files.getLastModifiedTime(configPath).toMillis() }
            .getOrElse { return null }

        cacheByProject[projectKey]?.let { cached ->
            if (cached.path == configPath && cached.lastModifiedMillis == modifiedAt) {
                return cached.config
            }
        }

        val text = runCatching { Files.readString(configPath, StandardCharsets.UTF_8) }
            .getOrElse { return null }

        val parsed = parseConfig(text)
        cacheByProject[projectKey] = CacheEntry(configPath, modifiedAt, parsed)
        return parsed
    }

    private fun parseConfig(text: String): EndpointConfig {
        val scalars = linkedMapOf<String, String>()
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var activeSection: String? = null

        text.lineSequence().forEach { rawLine ->
            val content = stripInlineComment(rawLine)
            if (content.isBlank()) return@forEach

            val indent = content.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val trimmed = content.trim()
            val delimiter = trimmed.indexOf(':')
            if (delimiter <= 0) return@forEach

            val key = unquote(trimmed.substring(0, delimiter).trim())
            val value = trimmed.substring(delimiter + 1).trim()

            if (indent == 0) {
                if (value.isEmpty()) {
                    activeSection = canonicalKey(key)
                    sections.putIfAbsent(activeSection!!, linkedMapOf())
                } else {
                    scalars[canonicalKey(key)] = unquote(value)
                    activeSection = null
                }
                return@forEach
            }

            val section = activeSection ?: return@forEach
            if (value.isEmpty()) return@forEach
            sections.getOrPut(section) { linkedMapOf() }[unquote(key)] = unquote(value)
        }

        val environments = sectionValues(sections, "environments")
            .mapNotNull { (key, value) ->
                val normalized = normalizeBaseUrl(value) ?: return@mapNotNull null
                key.trim().takeIf { it.isNotBlank() }?.let { it to normalized }
            }
            .toMap()

        val defaultEnv = scalarValue(scalars, "defaultEnv", "default_env")
        val configuredBaseRef = scalarValue(scalars, "baseUrl", "base_url")

        val serviceBaseUrls = sectionValues(
            sections,
            "serviceBaseUrls",
            "service_base_urls"
        )
        val servicePaths = sectionValues(sections, "servicePaths", "service_paths")
        val serviceRequestTypes = sectionValues(
            sections,
            "serviceRequestTypes",
            "service_request_types"
        )
        val serviceResponseTypes = sectionValues(
            sections,
            "serviceResponseTypes",
            "service_response_types"
        )

        val globalBaseUrl = resolveBaseUrlReference(
            reference = configuredBaseRef ?: defaultEnv,
            environments = environments
        )

        return EndpointConfig(
            globalBaseUrl = globalBaseUrl,
            environments = environments,
            serviceBaseUrls = serviceBaseUrls,
            servicePaths = servicePaths,
            serviceRequestTypes = serviceRequestTypes,
            serviceResponseTypes = serviceResponseTypes
        )
    }

    private fun sectionValues(sections: Map<String, Map<String, String>>, vararg names: String): Map<String, String> {
        for (name in names) {
            val found = sections[canonicalKey(name)]
            if (!found.isNullOrEmpty()) {
                return found
            }
        }
        return emptyMap()
    }

    private fun scalarValue(scalars: Map<String, String>, vararg keys: String): String? {
        for (key in keys) {
            val value = scalars[canonicalKey(key)]
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun canonicalKey(key: String): String {
        return key.lowercase().replace("_", "").replace("-", "")
    }

    /**
     * Drops inline comments while preserving `#` inside quoted strings.
     */
    private fun stripInlineComment(line: String): String {
        var quote: Char? = null
        var escaped = false

        for (index in line.indices) {
            val ch = line[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (quote != null) {
                if (ch == quote) {
                    quote = null
                }
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                continue
            }
            if (ch == '#') {
                return line.substring(0, index)
            }
        }

        return line
    }

    private fun unquote(raw: String): String {
        return raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
    }

    /**
     * Normalizes configured paths into absolute path form expected by the UI/docs.
     */
    private fun normalizePath(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return "/"
        return if (value.startsWith("/")) value else "/$value"
    }

    /**
     * Supports absolute URL path overrides by splitting host + path components.
     */
    private fun parseAbsolutePathOverride(raw: String): AbsolutePathOverride? {
        val value = raw.trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val authority = uri.authority ?: return null
        val baseUrl = "$scheme://$authority".trimEnd('/')
        val rawPath = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return AbsolutePathOverride(path = normalizePath("$rawPath$query"), baseUrl = baseUrl)
    }

    private fun normalizeBaseUrl(raw: String): String? {
        val value = unquote(raw)
        if (value.isEmpty()) return null
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null
        return value.trimEnd('/')
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun <T> firstNonNull(vararg values: T?): T? {
        return values.firstOrNull { it != null }
    }

    private fun resolveBaseUrlReference(reference: String?, environments: Map<String, String>): String? {
        if (reference.isNullOrBlank()) return null
        normalizeBaseUrl(reference)?.let { return it }

        val matched = environments.entries.firstOrNull { (name, _) ->
            name.equals(reference.trim(), ignoreCase = true)
        } ?: return null

        return matched.value
    }

    private data class EndpointConfig(
        val globalBaseUrl: String?,
        val environments: Map<String, String>,
        val serviceBaseUrls: Map<String, String>,
        val servicePaths: Map<String, String>,
        val serviceRequestTypes: Map<String, String>,
        val serviceResponseTypes: Map<String, String>
    ) {
        fun resolveBaseUrl(value: String?): String? {
            return resolveBaseUrlReference(value, environments)
        }
    }

    private data class AbsolutePathOverride(
        val path: String,
        val baseUrl: String
    )

    private data class CacheEntry(
        val path: Path,
        val lastModifiedMillis: Long,
        val config: EndpointConfig
    )

    private val TEMPLATE = """
# EndpointDroid manual overrides
#
# You can keep this minimal (only baseUrl) or add targeted overrides.

baseUrl: https://api.example.com
# defaultEnv: dev

# environments:
#   dev: https://dev.api.example.com
#   stage: https://stage.api.example.com
#   prod: https://api.example.com

# serviceBaseUrls:
#   com.example.api.SotwApi: prod
#   com.example.api.AuthApi#login: https://auth.api.example.com

# servicePaths:
#   com.example.api.AuthApi#login: /v1/login

# serviceRequestTypes:
#   com.example.api.AuthApi#login: LoginRequest

# serviceResponseTypes:
#   com.example.api.AuthApi#login: TokenResponse
""".trimIndent()
}
