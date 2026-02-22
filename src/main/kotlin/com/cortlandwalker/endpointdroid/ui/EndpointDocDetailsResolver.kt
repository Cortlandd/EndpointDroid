package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves method-level Retrofit metadata for a selected endpoint.
 *
 * This is intentionally best-effort; unresolved data produces empty details.
 */
internal object EndpointDocDetailsResolver {
    private const val RETROFIT_PREFIX = "retrofit2.http."
    private const val AUTHORIZATION_HEADER = "Authorization"
    private const val CONFIG_FILE_NAME = "endpointdroid.yaml"
    private const val CONFIG_BASE_URL_KEY = "baseUrl"
    private const val CONFIG_BASE_URL_SNAKE_KEY = "base_url"
    private const val MAX_CACHE_ENTRIES = 2048
    private val detailsCache = ConcurrentHashMap<DetailsCacheKey, EndpointDocDetails>()

    /**
     * Resolves rich details for an endpoint's source method.
     */
    fun resolve(project: Project, endpoint: Endpoint): EndpointDocDetails {
        val cacheKey = DetailsCacheKey(
            projectKey = project.basePath ?: project.locationHash,
            psiModificationCount = PsiModificationTracker.getInstance(project).modificationCount,
            httpMethod = endpoint.httpMethod,
            path = endpoint.path,
            serviceFqn = endpoint.serviceFqn,
            functionName = endpoint.functionName
        )
        detailsCache[cacheKey]?.let { return it }

        val baseUrlFromConfig = isConfiguredBaseUrl(project, endpoint.baseUrl)
        val method = findEndpointMethod(project, endpoint) ?: return cacheAndReturn(
            cacheKey,
            EndpointDocDetails.empty().copy(baseUrlFromConfig = baseUrlFromConfig)
        )
        val source = resolveSourceLocation(project, method)

        val pathParams = mutableListOf<String>()
        val queryParams = mutableListOf<String>()
        val queryParamDetails = mutableListOf<EndpointDocDetails.QueryParamDetails>()
        var hasQueryMap = false
        val headerParams = mutableListOf<String>()
        var hasHeaderMap = false
        val fieldParams = mutableListOf<String>()
        var hasFieldMap = false
        val partParams = mutableListOf<String>()
        var hasPartMap = false
        var hasDynamicUrl = false
        var hasBody = false
        val parameterDefaults = extractKotlinDefaultValues(method)

        method.parameterList.parameters.forEach { param ->
            param.annotations.forEach { ann ->
                when (ann.qualifiedName) {
                    "retrofit2.http.Path",
                    "retrofit2.http.Param" -> pathParams += annotationNameOrFallback(ann, param.name ?: "path")

                    "retrofit2.http.Query" -> {
                        val queryName = annotationNameOrFallback(ann, param.name ?: "query")
                        queryParams += queryName
                        val defaultValue = param.name?.let(parameterDefaults::get)
                        queryParamDetails += EndpointDocDetails.QueryParamDetails(
                            name = queryName,
                            type = normalizeParameterType(param.type.presentableText),
                            required = isQueryRequired(param, defaultValue),
                            defaultValue = defaultValue
                        )
                    }
                    "retrofit2.http.QueryMap" -> hasQueryMap = true
                    "retrofit2.http.Header" -> headerParams += annotationNameOrFallback(ann, param.name ?: "header")
                    "retrofit2.http.HeaderMap" -> hasHeaderMap = true
                    "retrofit2.http.Field" -> fieldParams += annotationNameOrFallback(ann, param.name ?: "field")
                    "retrofit2.http.FieldMap" -> hasFieldMap = true
                    "retrofit2.http.Part" -> partParams += annotationNameOrFallback(ann, param.name ?: "part")
                    "retrofit2.http.PartMap" -> hasPartMap = true
                    "retrofit2.http.Url" -> hasDynamicUrl = true
                    "retrofit2.http.Body" -> hasBody = true
                }
            }
        }

        val staticHeaders = collectStaticHeaders(method.containingClass, method)
        val hasAuthStaticHeader = staticHeaders.any { headerLine ->
            headerLine.substringBefore(':').trim().equals(AUTHORIZATION_HEADER, ignoreCase = true)
        }
        val hasAuthHeaderParam = headerParams.any { it.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
        val authRequirement = when {
            hasAuthStaticHeader || hasAuthHeaderParam -> EndpointDocDetails.AuthRequirement.REQUIRED
            hasHeaderMap -> EndpointDocDetails.AuthRequirement.OPTIONAL
            else -> EndpointDocDetails.AuthRequirement.NONE
        }
        val requestType = extractBodyType(method) ?: endpoint.requestType
        val responseType = extractResponseType(method) ?: endpoint.responseType
        val requestSamples = EndpointJsonSampleResolver.build(project, requestType)
        val responseSamples = EndpointJsonSampleResolver.build(project, responseType)

        val resolved = EndpointDocDetails(
            sourceFile = source.file,
            sourceLine = source.line,
            baseUrlFromConfig = baseUrlFromConfig,
            pathParams = pathParams.distinct(),
            queryParams = queryParams.distinct(),
            queryParamDetails = queryParamDetails.distinctBy { it.name },
            hasQueryMap = hasQueryMap,
            headerParams = headerParams.distinct(),
            hasHeaderMap = hasHeaderMap,
            fieldParams = fieldParams.distinct(),
            hasFieldMap = hasFieldMap,
            partParams = partParams.distinct(),
            hasPartMap = hasPartMap,
            hasDynamicUrl = hasDynamicUrl,
            hasBody = hasBody,
            staticHeaders = staticHeaders.distinct(),
            requestSchemaJson = requestSamples?.schemaJson,
            requestExampleJson = requestSamples?.exampleJson,
            responseSchemaJson = responseSamples?.schemaJson,
            responseExampleJson = responseSamples?.exampleJson,
            authRequirement = authRequirement
        )

        return cacheAndReturn(cacheKey, resolved)
    }

    /**
     * Parses default parameter expressions from Kotlin function declarations when available.
     */
    private fun extractKotlinDefaultValues(method: PsiMethod): Map<String, String> {
        val sourceText = method.navigationElement
            .takeIf { it.isValid }
            ?.text
            ?.takeIf { it.contains(':') && it.contains('=') }
            ?: return emptyMap()

        val result = LinkedHashMap<String, String>()
        val defaultRegex = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:\s*[^=,\n)]+=\s*([^,\n)]+)""")
        defaultRegex.findAll(sourceText).forEach { match ->
            val paramName = match.groupValues[1].trim()
            val defaultValue = match.groupValues[2].trim()
            if (paramName.isNotEmpty() && defaultValue.isNotEmpty()) {
                result[paramName] = defaultValue
            }
        }
        return result
    }

    /**
     * Normalizes presentable type text for query parameter tables.
     */
    private fun normalizeParameterType(typeText: String): String {
        return typeText
            .removePrefix("kotlin.")
            .removePrefix("java.lang.")
            .trim()
            .ifBlank { "?" }
    }

    /**
     * Determines whether query params must be supplied at runtime.
     */
    private fun isQueryRequired(param: PsiParameter, defaultValue: String?): Boolean {
        if (defaultValue != null) return false
        val nullable = param.annotations.any { ann ->
            val qName = ann.qualifiedName ?: return@any false
            qName.endsWith(".Nullable") || qName == "Nullable"
        }
        return !nullable
    }

    /**
     * Checks whether the endpoint base URL matches an explicit config override value.
     */
    private fun isConfiguredBaseUrl(project: Project, endpointBaseUrl: String?): Boolean {
        val normalizedEndpointBase = endpointBaseUrl?.trim()?.trimEnd('/') ?: return false
        val basePath = project.basePath ?: return false
        val configPath = java.nio.file.Path.of(basePath, CONFIG_FILE_NAME)
        if (!Files.isRegularFile(configPath)) return false

        val lines = runCatching { Files.readAllLines(configPath, StandardCharsets.UTF_8) }
            .getOrNull()
            ?: return false

        for (line in lines) {
            val content = line.substringBefore('#').trim()
            if (content.isEmpty()) continue

            val key = when {
                content.startsWith("$CONFIG_BASE_URL_KEY:") -> CONFIG_BASE_URL_KEY
                content.startsWith("$CONFIG_BASE_URL_SNAKE_KEY:") -> CONFIG_BASE_URL_SNAKE_KEY
                else -> null
            } ?: continue

            val configuredBase = content
                .removePrefix("$key:")
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
                .trimEnd('/')

            if (configuredBase.equals(normalizedEndpointBase, ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    /**
     * Stores a details entry in the bounded cache and returns it.
     */
    private fun cacheAndReturn(key: DetailsCacheKey, details: EndpointDocDetails): EndpointDocDetails {
        if (detailsCache.size >= MAX_CACHE_ENTRIES) {
            detailsCache.clear()
        }
        detailsCache[key] = details
        return details
    }

    /**
     * Locates the PSI method backing a scanned endpoint.
     */
    private fun findEndpointMethod(project: Project, endpoint: Endpoint): PsiMethod? {
        val javaFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val serviceClass = javaFacade.findClass(endpoint.serviceFqn, scope) ?: return null
        val candidates = serviceClass.methods.filter { candidate ->
            candidate.name == endpoint.functionName && hasRetrofitHttpAnnotation(candidate)
        }
        return candidates.firstOrNull { candidate ->
            val http = extractHttpSignature(candidate)
            http?.method.equals(endpoint.httpMethod, ignoreCase = true) && http?.path == endpoint.path
        } ?: candidates.firstOrNull()
    }

    /**
     * Extracts HTTP method + normalized path from a Retrofit-annotated method.
     */
    private fun extractHttpSignature(method: PsiMethod): HttpSignature? {
        method.modifierList.annotations.forEach { ann ->
            val qName = ann.qualifiedName ?: return@forEach
            if (!qName.startsWith(RETROFIT_PREFIX)) return@forEach

            val short = qName.substringAfterLast('.')
            if (short !in retrofitHttpAnnotationNames) return@forEach

            val rawPath = ann.findAttributeValue("value")?.text?.trim('"')
                ?: ann.parameterList.attributes.firstOrNull()?.value?.text?.trim('"')
                ?: ""

            if (short == "HTTP") {
                val customMethod = ann.findAttributeValue("method")?.text?.trim('"') ?: "GET"
                val customPath = ann.findAttributeValue("path")?.text?.trim('"') ?: rawPath
                return HttpSignature(customMethod, normalizePath(customPath))
            }

            return HttpSignature(short, normalizePath(rawPath))
        }
        return null
    }

    /**
     * Reads file + line information from a method navigation element.
     */
    private fun resolveSourceLocation(project: Project, method: PsiMethod): SourceLocation {
        val navElement = method.navigationElement.takeIf { it.isValid } ?: method
        val vFile = navElement.containingFile?.virtualFile ?: return SourceLocation(null, null)
        val document = FileDocumentManager.getInstance().getDocument(vFile)
        val line = document?.getLineNumber(navElement.textOffset)?.plus(1)

        val basePath = project.basePath
        val relativePath = if (basePath == null) {
            vFile.path
        } else {
            val full = Path(vFile.path).invariantSeparatorsPathString
            val base = Path(basePath).invariantSeparatorsPathString.trimEnd('/')
            if (full.startsWith("$base/")) full.removePrefix("$base/") else vFile.path
        }

        return SourceLocation(relativePath, line)
    }

    /**
     * Collects static header literals from service and method @Headers annotations.
     */
    private fun collectStaticHeaders(serviceClass: PsiClass?, method: PsiMethod): List<String> {
        val result = mutableListOf<String>()
        serviceClass?.modifierList?.annotations.orEmpty().forEach { ann ->
            if (ann.qualifiedName == "retrofit2.http.Headers") {
                result += extractHeaderValues(ann)
            }
        }
        method.modifierList.annotations.forEach { ann ->
            if (ann.qualifiedName == "retrofit2.http.Headers") {
                result += extractHeaderValues(ann)
            }
        }
        return result
    }

    /**
     * Extracts string values from @Headers annotation expressions.
     */
    private fun extractHeaderValues(annotation: PsiAnnotation): List<String> {
        val attribute = annotation.findAttributeValue("value") ?: return emptyList()
        return Regex("\"([^\"]+)\"")
            .findAll(attribute.text)
            .map { it.groupValues[1] }
            .toList()
    }

    /**
     * Resolves annotation value/name fallback for parameter placeholders.
     */
    private fun annotationNameOrFallback(annotation: PsiAnnotation, fallback: String): String {
        val named = annotation.findAttributeValue("value")?.text?.trim('"')?.takeIf { it.isNotBlank() }
        if (named != null) return named
        val positional = annotation.parameterList.attributes.firstOrNull()
            ?.value
            ?.text
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
        return positional ?: fallback
    }

    /**
     * Extracts the first Retrofit `@Body` parameter type from the method signature.
     */
    private fun extractBodyType(method: PsiMethod): String? {
        val bodyParam = method.parameterList.parameters.firstOrNull { param ->
            param.annotations.any { ann -> ann.qualifiedName == "retrofit2.http.Body" }
        } ?: return null
        return bodyParam.type.presentableText
    }

    /**
     * Extracts a best-effort response payload type from Retrofit return signatures.
     */
    private fun extractResponseType(method: PsiMethod): String? {
        val returnType = method.returnType ?: return null
        val presentable = returnType.presentableText

        fun unwrap(raw: String): String? {
            val prefix = "$raw<"
            if (!presentable.startsWith(prefix)) return null
            return presentable.removePrefix(prefix).removeSuffix(">")
        }

        unwrap("Call")?.let { return it }
        unwrap("Response")?.let { return it }

        if (presentable == "Object" || presentable == "Any") {
            extractSuspendContinuationType(method)?.let { return it }
        }

        return presentable
    }

    /**
     * Extracts Kotlin suspend return type from trailing `Continuation<T>` parameter.
     */
    private fun extractSuspendContinuationType(method: PsiMethod): String? {
        val continuationType = method.parameterList.parameters
            .lastOrNull()
            ?.type
            ?.presentableText
            ?: return null

        val match = Regex("""(?:kotlin\.coroutines\.)?Continuation<(.+)>""")
            .matchEntire(continuationType)
            ?: return null

        val raw = match.groupValues[1]
            .removePrefix("? super ")
            .removePrefix("? extends ")
            .trim()

        return raw.takeIf { it.isNotBlank() }
    }

    /**
     * Checks whether a method has one of Retrofit's HTTP annotations.
     */
    private fun hasRetrofitHttpAnnotation(method: PsiMethod): Boolean {
        return method.modifierList.annotations.any { ann ->
            val qName = ann.qualifiedName ?: return@any false
            qName.startsWith(RETROFIT_PREFIX) &&
                qName.substringAfterLast('.') in retrofitHttpAnnotationNames
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        return if (path.startsWith("/")) path else "/$path"
    }

    private data class SourceLocation(val file: String?, val line: Int?)
    private data class HttpSignature(val method: String, val path: String)
    private data class DetailsCacheKey(
        val projectKey: String,
        val psiModificationCount: Long,
        val httpMethod: String,
        val path: String,
        val serviceFqn: String,
        val functionName: String
    )

    private val retrofitHttpAnnotationNames = setOf(
        "GET",
        "POST",
        "PUT",
        "PATCH",
        "DELETE",
        "HEAD",
        "OPTIONS",
        "HTTP"
    )
}
