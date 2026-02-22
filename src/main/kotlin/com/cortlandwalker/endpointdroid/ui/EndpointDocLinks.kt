package com.cortlandwalker.endpointdroid.ui

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds and parses markdown hyperlink targets used inside endpoint docs.
 */
internal object EndpointDocLinks {
    private const val SCHEME = "endpointdroid"
    private const val HOST = "navigate"
    private const val KIND_KEY = "kind"
    private const val KIND_FUNCTION = "function"
    private const val KIND_SERVICE = "service"
    private const val KIND_TYPE = "type"
    private const val KIND_USAGE = "usage"
    private const val SERVICE_KEY = "service"
    private const val FUNCTION_KEY = "function"
    private const val TYPE_KEY = "type"
    private const val FILE_KEY = "file"
    private const val OFFSET_KEY = "offset"

    /**
     * Navigation targets represented by custom endpoint hyperlinks.
     */
    sealed interface Target {
        data class Function(val serviceFqn: String, val functionName: String) : Target
        data class Service(val serviceFqn: String) : Target
        data class Type(val typeText: String) : Target
        data class Usage(val filePath: String, val offset: Int) : Target
    }

    /**
     * Link that opens the endpoint function at its declaration line.
     */
    fun functionUrl(serviceFqn: String, functionName: String): String {
        return buildUrl(
            KIND_KEY to KIND_FUNCTION,
            SERVICE_KEY to serviceFqn,
            FUNCTION_KEY to functionName
        )
    }

    /**
     * Link that opens the service/interface declaration.
     */
    fun serviceUrl(serviceFqn: String): String {
        return buildUrl(
            KIND_KEY to KIND_SERVICE,
            SERVICE_KEY to serviceFqn
        )
    }

    /**
     * Link that attempts to open a type declaration.
     */
    fun typeUrl(typeText: String): String {
        return buildUrl(
            KIND_KEY to KIND_TYPE,
            TYPE_KEY to typeText
        )
    }

    /**
     * Link that opens a specific usage location in source.
     */
    fun usageUrl(filePath: String, offset: Int): String {
        return buildUrl(
            KIND_KEY to KIND_USAGE,
            FILE_KEY to filePath,
            OFFSET_KEY to offset.toString()
        )
    }

    /**
     * Parses a custom endpoint hyperlink into a navigation target.
     */
    fun parse(url: String): Target? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME) return null

        val params = parseQuery(uri.rawQuery ?: return null)
        return when (params[KIND_KEY]) {
            KIND_FUNCTION -> {
                val serviceFqn = params[SERVICE_KEY] ?: return null
                val functionName = params[FUNCTION_KEY] ?: return null
                Target.Function(serviceFqn, functionName)
            }

            KIND_SERVICE -> {
                val serviceFqn = params[SERVICE_KEY] ?: return null
                Target.Service(serviceFqn)
            }

            KIND_TYPE -> {
                val typeText = params[TYPE_KEY] ?: return null
                Target.Type(typeText)
            }

            KIND_USAGE -> {
                val filePath = params[FILE_KEY] ?: return null
                val offset = params[OFFSET_KEY]?.toIntOrNull() ?: return null
                Target.Usage(filePath, offset)
            }

            else -> null
        }
    }

    /**
     * Builds a fully encoded custom URL from query params.
     */
    private fun buildUrl(vararg params: Pair<String, String>): String {
        val query = params.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "$SCHEME://$HOST?$query"
    }

    /**
     * Parses and decodes query parameters from a URI query string.
     */
    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = decode(pair.substring(0, idx))
                val value = decode(pair.substring(idx + 1))
                key to value
            }
            .toMap()
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
