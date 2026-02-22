package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise

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
    @Volatile
    private var refreshPromise: CancellablePromise<List<Endpoint>>? = null

    fun getEndpoints(): List<Endpoint> = endpoints

    /**
     * Refreshes the endpoint cache by scanning the project with non-blocking read action semantics.
     *
     * Important:
     * - Uses IntelliJ's smart-mode-aware non-blocking read action scheduling.
     * - Cancels older in-flight refreshes so only the latest scan wins.
     */
    fun refreshAsync(): CancellablePromise<List<Endpoint>> {
        refreshPromise?.cancel()

        val promise = ReadAction
            .nonBlocking<List<Endpoint>> {
                RetrofitEndpointScanner.scan(project)
            }
            .inSmartMode(project)
            .expireWith(project)
            .coalesceBy(this, "endpoint-refresh")
            .submit(AppExecutorUtil.getAppExecutorService())

        refreshPromise = promise
        promise.onSuccess { discovered ->
            endpoints = discovered
        }

        return promise
    }

    companion object {
        fun getInstance(project: Project): EndpointService =
            project.getService(EndpointService::class.java)
    }
}
