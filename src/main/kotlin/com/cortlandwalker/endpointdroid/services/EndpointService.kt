package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.CancellationException

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
    @Volatile
    private var lastRefreshStats: RefreshStats = RefreshStats.initial()

    fun getEndpoints(): List<Endpoint> = endpoints
    fun getLastRefreshStatus(): String = lastRefreshStats.toStatusText()

    /**
     * Refreshes the endpoint cache by scanning the project with non-blocking read action semantics.
     *
     * Important:
     * - Uses IntelliJ's smart-mode-aware non-blocking read action scheduling.
     * - Cancels older in-flight refreshes so only the latest scan wins.
     */
    fun refreshAsync(): CancellablePromise<List<Endpoint>> {
        refreshPromise?.cancel()
        val startedAtNanos = System.nanoTime()

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
            val durationMs = nanosToMillis(System.nanoTime() - startedAtNanos)
            val stats = RefreshStats.success(discovered.size, durationMs)
            lastRefreshStats = stats
            LOG.info("Endpoint refresh completed: ${stats.endpointCount} endpoints in ${stats.durationMs} ms")
        }
        promise.onError { error ->
            val durationMs = nanosToMillis(System.nanoTime() - startedAtNanos)
            val stats = when (error) {
                is ProcessCanceledException, is CancellationException ->
                    RefreshStats.cancelled(durationMs)

                else -> RefreshStats.error(durationMs, error.message ?: error::class.java.simpleName)
            }
            lastRefreshStats = stats
            if (stats.errorMessage != null) {
                LOG.warn("Endpoint refresh failed after ${stats.durationMs} ms: ${stats.errorMessage}")
            } else {
                LOG.info("Endpoint refresh cancelled after ${stats.durationMs} ms")
            }
        }

        return promise
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    companion object {
        private val LOG = Logger.getInstance(EndpointService::class.java)

        fun getInstance(project: Project): EndpointService =
            project.getService(EndpointService::class.java)
    }

    /**
     * Captures summary timing/count outcome for the latest refresh attempt.
     */
    data class RefreshStats(
        val endpointCount: Int,
        val durationMs: Long,
        val errorMessage: String?,
        val cancelled: Boolean
    ) {
        fun toStatusText(): String {
            return when {
                errorMessage != null -> "Last scan failed in $durationMs ms: $errorMessage"
                cancelled -> "Last scan cancelled after $durationMs ms"
                else -> "Last scan: $endpointCount endpoints in $durationMs ms"
            }
        }

        companion object {
            fun initial(): RefreshStats = RefreshStats(0, 0, null, false)
            fun success(count: Int, durationMs: Long): RefreshStats = RefreshStats(count, durationMs, null, false)
            fun cancelled(durationMs: Long): RefreshStats = RefreshStats(0, durationMs, null, true)
            fun error(durationMs: Long, message: String): RefreshStats = RefreshStats(0, durationMs, message, false)
        }
    }
}
