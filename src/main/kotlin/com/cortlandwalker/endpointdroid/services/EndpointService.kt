package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service that owns the current snapshot of discovered API endpoints.
 *
 * v0 behavior:
 * - [refresh] performs a best-effort scan for Retrofit endpoints.
 * - Future steps add baseUrl inference/config and additional providers (Ktor/OkHttp/etc).
 */
@Service(Service.Level.PROJECT)
class EndpointService(private val project: Project) {

    @Volatile
    private var endpoints: List<Endpoint> = emptyList()

    fun getEndpoints(): List<Endpoint> = endpoints

    /**
     * Refreshes the endpoint cache by scanning the project.
     *
     * Important:
     * - This is a synchronous call for MVP simplicity.
     * - Later we should run scans off the EDT and update UI safely.
     */
    fun refresh() {
        val project = this.project
        val discovered = com.intellij.openapi.application.runReadAction {
            RetrofitEndpointScanner.scan(project)
        }
        endpoints = discovered
    }

    companion object {
        fun getInstance(project: Project): EndpointService =
            project.getService(EndpointService::class.java)
    }
}
