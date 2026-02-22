package com.cortlandwalker.endpointdroid.ui

/**
 * Parsed endpoint details used to enrich documentation and HTTP draft output.
 */
internal data class EndpointDocDetails(
    val sourceFile: String?,
    val sourceLine: Int?,
    val baseUrlFromConfig: Boolean,
    val pathParams: List<String>,
    val queryParams: List<String>,
    val queryParamDetails: List<QueryParamDetails>,
    val hasQueryMap: Boolean,
    val headerParams: List<String>,
    val hasHeaderMap: Boolean,
    val fieldParams: List<String>,
    val hasFieldMap: Boolean,
    val partParams: List<String>,
    val hasPartMap: Boolean,
    val hasDynamicUrl: Boolean,
    val hasBody: Boolean,
    val staticHeaders: List<String>,
    val requestSchemaJson: String?,
    val requestExampleJson: String?,
    val responseSchemaJson: String?,
    val responseExampleJson: String?,
    val authRequirement: AuthRequirement
) {
    /**
     * Query parameter metadata used for richer table rendering in endpoint docs.
     */
    data class QueryParamDetails(
        val name: String,
        val type: String,
        val required: Boolean,
        val defaultValue: String?
    )

    /**
     * Indicates whether authorization header is required, optional, or absent.
     */
    enum class AuthRequirement {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    companion object {
        fun empty(): EndpointDocDetails = EndpointDocDetails(
            sourceFile = null,
            sourceLine = null,
            baseUrlFromConfig = false,
            pathParams = emptyList(),
            queryParams = emptyList(),
            queryParamDetails = emptyList(),
            hasQueryMap = false,
            headerParams = emptyList(),
            hasHeaderMap = false,
            fieldParams = emptyList(),
            hasFieldMap = false,
            partParams = emptyList(),
            hasPartMap = false,
            hasDynamicUrl = false,
            hasBody = false,
            staticHeaders = emptyList(),
            requestSchemaJson = null,
            requestExampleJson = null,
            responseSchemaJson = null,
            responseExampleJson = null,
            authRequirement = AuthRequirement.NONE
        )
    }
}
