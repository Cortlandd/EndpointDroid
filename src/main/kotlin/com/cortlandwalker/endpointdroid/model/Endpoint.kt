package com.cortlandwalker.endpointdroid.model

data class Endpoint(
    val httpMethod: String,
    val path: String,
    val serviceFqn: String,
    val functionName: String,
    val requestType: String?,
    val responseType: String?,
    val baseUrl: String?
)