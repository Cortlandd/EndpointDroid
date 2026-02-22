package com.cortlandwalker.endpointdroid.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.util.PsiModificationTracker
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a best-effort Retrofit base URL for a project.
 *
 * Resolution order:
 * 1. `endpointdroid.yaml` override in project root (`baseUrl` key).
 * 2. Source inference from `Retrofit.Builder().baseUrl(...)` calls in `.kt`/`.java` files.
 *
 * Notes:
 * - v0 returns a single global base URL for all endpoints.
 * - This keeps docs/export immediately runnable without provider-specific modeling.
 */
internal object BaseUrlResolver {

    private const val CONFIG_FILE_NAME = "endpointdroid.yaml"
    private const val CONFIG_BASE_URL_KEY = "baseUrl"
    private const val CONFIG_BASE_URL_SNAKE_KEY = "base_url"

    private val baseUrlCallRegex = Regex("""\.baseUrl\s*\(\s*([^)]+?)\s*\)""")
    private val kotlinStringConstantRegex =
        Regex("(?:const\\s+)?val\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]+)\"")
    private val javaStringConstantRegex =
        Regex("String\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]+)\"")
    private val identifierRegex = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
    private val cacheByKey = ConcurrentHashMap<ResolutionCacheKey, ResolutionCacheValue>()
    private val latestKeyByProject = ConcurrentHashMap<String, ResolutionCacheKey>()

    /**
     * Resolves the base URL for the given project, or `null` when none is discovered.
     */
    fun resolve(project: Project): String? {
        val projectKey = project.basePath ?: project.locationHash
        val cacheKey = ResolutionCacheKey(
            projectKey = projectKey,
            psiModificationCount = PsiModificationTracker.getInstance(project).modificationCount,
            configLastModifiedMillis = configLastModifiedMillis(project)
        )
        val latestKey = latestKeyByProject[projectKey]
        if (latestKey == cacheKey) {
            cacheByKey[cacheKey]?.let { return it.baseUrl }
        }

        val resolved = readBaseUrlFromConfig(project) ?: inferBaseUrlFromSource(project)

        cacheByKey[cacheKey] = ResolutionCacheValue(resolved)
        latestKeyByProject.put(projectKey, cacheKey)?.let { previousKey ->
            if (previousKey != cacheKey) {
                cacheByKey.remove(previousKey)
            }
        }

        return resolved
    }

    /**
     * Reads a user override from `endpointdroid.yaml`.
     */
    private fun readBaseUrlFromConfig(project: Project): String? {
        val basePath = project.basePath ?: return null
        val configPath = Path.of(basePath, CONFIG_FILE_NAME)
        if (!Files.isRegularFile(configPath)) return null

        val lines = runCatching { Files.readAllLines(configPath, StandardCharsets.UTF_8) }
            .getOrNull()
            ?: return null

        for (line in lines) {
            val content = line.substringBefore('#').trim()
            if (content.isEmpty()) continue

            val key = when {
                content.startsWith("$CONFIG_BASE_URL_KEY:") -> CONFIG_BASE_URL_KEY
                content.startsWith("$CONFIG_BASE_URL_SNAKE_KEY:") -> CONFIG_BASE_URL_SNAKE_KEY
                else -> null
            } ?: continue

            val rawValue = content.removePrefix("$key:").trim()
            normalizeBaseUrl(rawValue)?.let { return it }
        }

        return null
    }

    /**
     * Reads config file modification stamp for cache invalidation.
     */
    private fun configLastModifiedMillis(project: Project): Long {
        val basePath = project.basePath ?: return -1L
        val configPath = Path.of(basePath, CONFIG_FILE_NAME)
        if (!Files.isRegularFile(configPath)) return -1L
        return runCatching { Files.getLastModifiedTime(configPath).toMillis() }.getOrElse { -1L }
    }

    /**
     * Infers base URL from project source by inspecting Retrofit builder calls and simple constants.
     */
    private fun inferBaseUrlFromSource(project: Project): String? {
        val constantsByName = linkedMapOf<String, String>()
        val baseUrlArgs = mutableListOf<String>()

        val fileIndex = ProjectFileIndex.getInstance(project)
        fileIndex.iterateContent { file ->
            if (!isSupportedSourceFile(file.name)) return@iterateContent true

            val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@iterateContent true
            collectStringConstants(text, constantsByName)
            collectBaseUrlArguments(text, baseUrlArgs)
            true
        }

        // Prefer direct string literals to avoid ambiguous constant matches.
        for (arg in baseUrlArgs) {
            normalizeBaseUrl(arg)?.let { return it }
        }

        for (arg in baseUrlArgs) {
            if (!identifierRegex.matches(arg)) continue

            val direct = constantsByName[arg]
            if (direct != null) {
                normalizeBaseUrl(direct)?.let { return it }
            }

            val shortName = arg.substringAfterLast('.')
            val short = constantsByName[shortName]
            if (short != null) {
                normalizeBaseUrl(short)?.let { return it }
            }
        }

        return null
    }

    /**
     * Collects string constants that can later be referenced from `baseUrl(CONSTANT)` calls.
     */
    private fun collectStringConstants(text: String, constantsByName: MutableMap<String, String>) {
        kotlinStringConstantRegex.findAll(text).forEach { match ->
            constantsByName.putIfAbsent(match.groupValues[1], match.groupValues[2])
        }
        javaStringConstantRegex.findAll(text).forEach { match ->
            constantsByName.putIfAbsent(match.groupValues[1], match.groupValues[2])
        }
    }

    /**
     * Captures raw `baseUrl(...)` argument expressions for later resolution.
     */
    private fun collectBaseUrlArguments(text: String, baseUrlArgs: MutableList<String>) {
        baseUrlCallRegex.findAll(text).forEach { match ->
            baseUrlArgs += match.groupValues[1].trim()
        }
    }

    /**
     * Normalizes user/source URLs into a consistent format used by docs and export.
     */
    private fun normalizeBaseUrl(raw: String): String? {
        val unquoted = raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (unquoted.isEmpty()) return null
        if (!unquoted.startsWith("http://") && !unquoted.startsWith("https://")) return null
        return unquoted.trimEnd('/')
    }

    /**
     * Restricts text scanning to source files we can parse without extra language plugins.
     */
    private fun isSupportedSourceFile(fileName: String): Boolean {
        return fileName.endsWith(".kt") || fileName.endsWith(".java")
    }

    private data class ResolutionCacheKey(
        val projectKey: String,
        val psiModificationCount: Long,
        val configLastModifiedMillis: Long
    )

    private data class ResolutionCacheValue(val baseUrl: String?)
}
