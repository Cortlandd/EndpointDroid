package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint

/**
 * Stable endpoint key used for preserving selection, history, and metadata caches.
 */
internal data class EndpointKey(
    val httpMethod: String,
    val path: String,
    val serviceFqn: String,
    val functionName: String
) {
    companion object {
        /**
         * Creates a stable key for the given endpoint.
         */
        fun from(endpoint: Endpoint): EndpointKey {
            return EndpointKey(
                httpMethod = endpoint.httpMethod.uppercase(),
                path = endpoint.path,
                serviceFqn = endpoint.serviceFqn,
                functionName = endpoint.functionName
            )
        }
    }
}

/**
 * Lightweight metadata used for list badges and filters.
 */
internal data class EndpointListMetadata(
    val authRequirement: EndpointDocDetails.AuthRequirement?,
    val queryCount: Int,
    val hasMultipart: Boolean,
    val hasFormFields: Boolean,
    val baseUrlResolved: Boolean,
    val partial: Boolean
)

/**
 * Service group row model for the collapsible endpoint tree.
 */
internal data class EndpointServiceGroup(
    val serviceFqn: String,
    val count: Int
)
