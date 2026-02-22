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
    val authRequirement: AuthRequirement
) {
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
            authRequirement = AuthRequirement.NONE
        )
    }
}
