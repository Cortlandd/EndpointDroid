package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class EndpointService(private val project: Project) {

    @Volatile
    private var endpoints: List<Endpoint> = emptyList()

    fun getEndpoints(): List<Endpoint> = endpoints

    fun refresh() {
        endpoints = listOf(
            Endpoint(
                httpMethod = "GET",
                path = "/v1/users/{id}",
                serviceFqn = "com.example.api.UserService",
                functionName = "getUser",
                requestType = null,
                responseType = "UserResponse",
                baseUrl = "https://api.example.com"
            ),
            Endpoint(
                httpMethod = "POST",
                path = "/v1/auth/login",
                serviceFqn = "com.example.api.AuthService",
                functionName = "login",
                requestType = "LoginRequest",
                responseType = "TokenResponse",
                baseUrl = "https://api.example.com"
            )
        )
    }

    companion object {
        fun getInstance(project: Project): EndpointService =
            project.getService(EndpointService::class.java)
    }
}