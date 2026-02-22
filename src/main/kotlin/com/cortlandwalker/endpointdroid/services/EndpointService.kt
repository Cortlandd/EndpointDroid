package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service that owns the current snapshot of discovered API endpoints.
 *
 * Why a service?
 * - Tool windows can be opened/closed/recreated; the endpoint list should not live in UI state.
 * - Later, we’ll add background refresh and indexing; this service becomes the single source of truth.
 *
 * v0 behavior:
 * - [refresh] populates mock endpoints.
 * - In the next step, [refresh] will delegate to the Retrofit scanner.
 */
@Service(Service.Level.PROJECT)
class EndpointService(private val project: Project) {

    /**
     * Cached endpoints for the project.
     *
     * Marked volatile because UI and refresh operations may occur on different threads later.
     * v0 refresh runs on the UI thread, but we keep this safe from the start.
     */
    @Volatile
    private var endpoints: List<Endpoint> = emptyList()

    /**
     * Returns the most recently cached endpoints.
     *
     * @return immutable list of endpoints (may be empty).
     */
    fun getEndpoints(): List<Endpoint> = endpoints

    /**
     * Refreshes the endpoint cache.
     *
     * v0: mock data only.
     * Next: parse project source and return discovered Retrofit endpoints.
     */
    fun refresh() {
        // Step 2: still mock. Step 3 replaces this with Retrofit scanning.
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
        /**
         * Convenience accessor for retrieving this project service.
         */
        fun getInstance(project: Project): EndpointService =
            project.getService(EndpointService::class.java)
    }
}