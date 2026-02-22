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
 * Resolves method-level metadata for a selected endpoint (Retrofit and OkHttp).
 *
 * This is intentionally best-effort; unresolved data produces empty details.
 */
internal object EndpointDocDetailsResolver {
    private const val RETROFIT_PREFIX = "retrofit2.http."
    private const val OKHTTP_REQUEST_BUILDER = "Request.Builder"
    private const val AUTHORIZATION_HEADER = "Authorization"
    private const val CONFIG_FILE_NAME = "endpointdroid.yaml"
    private const val CONFIG_BASE_URL_KEY = "baseUrl"
    private const val CONFIG_BASE_URL_SNAKE_KEY = "base_url"
    private const val MAX_CACHE_ENTRIES = 2048
    private val okHttpUrlCallRegex = Regex("""\.url\s*\(\s*([^)]+?)\s*\)""")
    private val okHttpHeaderCallRegex = Regex("""\.(?:addHeader|header)\s*\(\s*"([^"]+)"\s*,\s*([^)]+)\)""")
    private val okHttpFormFieldRegex = Regex("""\.add(?:Encoded)?\s*\(\s*"([^"]+)"\s*,\s*([^)]+)\)""")
    private val okHttpMultipartPartRegex = Regex("\\.addFormDataPart\\s*\\(\\s*\"([^\"]+)\"")
    private val okHttpQueryNameRegex = Regex("""[?&]([A-Za-z0-9_.-]+)\s*=""")
    private val okHttpBodyMethodRegex = Regex("""\.(post|put|patch|delete)\s*\(""", RegexOption.IGNORE_CASE)
    private val okHttpMethodOverrideRegex = Regex("""\.method\s*\(\s*"([A-Za-z]+)"\s*,\s*([^)]+)\)""")
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
            EndpointDocDetails.empty().copy(
                providerLabel = inferProviderLabel(endpoint),
                baseUrlFromConfig = baseUrlFromConfig
            )
        )
        val source = resolveSourceLocation(project, method)
        val isRetrofitMethod = hasRetrofitHttpAnnotation(method)
        val providerLabel = when {
            isRetrofitMethod -> "Retrofit"
            looksLikeOkHttpMethod(method) || inferProviderLabel(endpoint) == "OkHttp" -> "OkHttp"
            else -> inferProviderLabel(endpoint)
        }

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
        var staticHeaders: List<String> = emptyList()
        val parameterDefaults = extractKotlinDefaultValues(method)
        if (isRetrofitMethod) {
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
        } else {
            val okHttpMethodDetails = extractOkHttpMethodDetails(method, parameterDefaults)
            pathParams += extractPathParamsFromPath(endpoint.path)
            pathParams += okHttpMethodDetails.pathParams
            queryParams += okHttpMethodDetails.queryParams
            queryParamDetails += okHttpMethodDetails.queryParamDetails
            headerParams += okHttpMethodDetails.headerParams
            fieldParams += okHttpMethodDetails.fieldParams
            partParams += okHttpMethodDetails.partParams
            hasDynamicUrl = okHttpMethodDetails.hasDynamicUrl
            hasBody = okHttpMethodDetails.hasBody
            staticHeaders = okHttpMethodDetails.staticHeaders
        }

        staticHeaders = if (isRetrofitMethod) {
            collectStaticHeaders(method.containingClass, method)
        } else staticHeaders
        val hasAuthStaticHeader = staticHeaders.any { headerLine ->
            headerLine.substringBefore(':').trim().equals(AUTHORIZATION_HEADER, ignoreCase = true)
        }
        val hasAuthHeaderParam = headerParams.any { it.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
        val authRequirement = when {
            hasAuthStaticHeader || hasAuthHeaderParam -> EndpointDocDetails.AuthRequirement.REQUIRED
            hasHeaderMap -> EndpointDocDetails.AuthRequirement.OPTIONAL
            else -> EndpointDocDetails.AuthRequirement.NONE
        }
        val requestType = if (isRetrofitMethod) {
            extractBodyType(method) ?: endpoint.requestType
        } else {
            inferOkHttpRequestType(method) ?: endpoint.requestType
        }
        val responseType = resolveResponseType(endpoint, method, isRetrofitMethod)
        val requestSamples = EndpointJsonSampleResolver.build(project, requestType)
        val responseSamples = EndpointJsonSampleResolver.build(project, responseType)

        val resolved = EndpointDocDetails(
            providerLabel = providerLabel,
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
        val retrofitCandidates = serviceClass.methods.filter { candidate ->
            candidate.name == endpoint.functionName && hasRetrofitHttpAnnotation(candidate)
        }
        retrofitCandidates.firstOrNull { candidate ->
            val http = extractHttpSignature(candidate)
            http?.method.equals(endpoint.httpMethod, ignoreCase = true) && http?.path == endpoint.path
        }?.let { return it }

        retrofitCandidates.firstOrNull()?.let { return it }
        return serviceClass.methods.firstOrNull { candidate -> candidate.name == endpoint.functionName }
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
     * Infers provider label from endpoint-level fields when method PSI is unavailable.
     */
    private fun inferProviderLabel(endpoint: Endpoint): String {
        val service = endpoint.serviceFqn.lowercase()
        return if (service.contains("okhttp")) "OkHttp" else "Retrofit"
    }

    /**
     * Detects whether a method likely builds OkHttp requests.
     */
    private fun looksLikeOkHttpMethod(method: PsiMethod): Boolean {
        val text = method.navigationElement
            .takeIf { it.isValid }
            ?.text
            ?: return false
        return text.contains(OKHTTP_REQUEST_BUILDER)
    }

    /**
     * Extracts best-effort details from methods that use OkHttp request-builder calls.
     */
    private fun extractOkHttpMethodDetails(
        method: PsiMethod,
        parameterDefaults: Map<String, String>
    ): OkHttpMethodDetails {
        val text = method.navigationElement
            .takeIf { it.isValid }
            ?.text
            ?: method.text

        val queryNames = LinkedHashSet<String>()
        okHttpUrlCallRegex.findAll(text).forEach { urlMatch ->
            val expression = urlMatch.groupValues[1]
            okHttpQueryNameRegex.findAll(expression).forEach { queryMatch ->
                queryNames += queryMatch.groupValues[1]
            }
        }

        val parameterByName = method.parameterList.parameters
            .mapNotNull { param -> param.name?.let { name -> name to param } }
            .toMap()
        val queryDetails = queryNames.map { queryName ->
            val param = parameterByName[queryName]
            val defaultValue = parameterDefaults[queryName]
            EndpointDocDetails.QueryParamDetails(
                name = queryName,
                type = normalizeParameterType(param?.type?.presentableText ?: "?"),
                required = if (param == null) true else isQueryRequired(param, defaultValue),
                defaultValue = defaultValue
            )
        }

        val headerNames = LinkedHashSet<String>()
        val staticHeaders = mutableListOf<String>()
        okHttpHeaderCallRegex.findAll(text).forEach { headerMatch ->
            val name = headerMatch.groupValues[1].trim()
            val valueRaw = headerMatch.groupValues[2].trim()
            if (name.isNotEmpty()) {
                headerNames += name
                val headerValue = valueRaw
                    .removeSuffix(")")
                    .trim()
                    .removeSurrounding("\"")
                    .ifBlank { "(dynamic)" }
                staticHeaders += "$name: $headerValue"
            }
        }

        val fieldNames = okHttpFormFieldRegex.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())

        val partNames = okHttpMultipartPartRegex.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())

        val pathParams = Regex("""\{([^}/]+)\}""")
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())

        val hasDynamicUrl = okHttpUrlCallRegex.findAll(text).any { urlMatch ->
            val expression = urlMatch.groupValues[1].trim()
            !expression.startsWith("\"") || expression.contains('$') || expression.contains('+')
        }

        val hasBody = okHttpBodyMethodRegex.containsMatchIn(text) ||
            okHttpMethodOverrideRegex.find(text)?.groupValues?.getOrNull(2)?.trim()?.let { arg ->
                arg.isNotBlank() && !arg.equals("null", ignoreCase = true)
            } == true

        return OkHttpMethodDetails(
            pathParams = pathParams.toList(),
            queryParams = queryNames.toList(),
            queryParamDetails = queryDetails,
            headerParams = headerNames.toList(),
            fieldParams = fieldNames.toList(),
            partParams = partNames.toList(),
            hasDynamicUrl = hasDynamicUrl,
            hasBody = hasBody,
            staticHeaders = staticHeaders
        )
    }

    /**
     * Best-effort request type inference for OkHttp request bodies.
     */
    private fun inferOkHttpRequestType(method: PsiMethod): String? {
        val text = method.navigationElement
            .takeIf { it.isValid }
            ?.text
            ?: method.text

        if (text.contains("MultipartBody.Builder")) return "MultipartBody"
        if (text.contains("FormBody.Builder")) return "FormBody"
        if (text.contains("RequestBody.create") || text.contains("toRequestBody(")) return "RequestBody"
        if (okHttpBodyMethodRegex.containsMatchIn(text)) return "RequestBody"
        return null
    }

    /**
     * Chooses response type with provider-aware fallback behavior.
     *
     * Retrofit methods usually declare the payload in method signatures, while many OkHttp
     * wrapper methods (e.g. `applyType`) return `Unit`/`Void`; those should not override a
     * previously inferred endpoint response type.
     */
    private fun resolveResponseType(endpoint: Endpoint, method: PsiMethod, isRetrofitMethod: Boolean): String? {
        if (isRetrofitMethod) {
            return extractResponseType(method) ?: endpoint.responseType
        }

        endpoint.responseType?.let { return it }
        val extracted = extractResponseType(method) ?: return null
        if (extracted.equals("Unit", ignoreCase = true) || extracted.equals("Void", ignoreCase = true)) {
            return null
        }
        return extracted
    }

    /**
     * Extracts Retrofit-style `{param}` segments from the endpoint path.
     */
    private fun extractPathParamsFromPath(path: String): List<String> {
        return Regex("""\{([^}/]+)\}""")
            .findAll(path)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
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
    private data class OkHttpMethodDetails(
        val pathParams: List<String>,
        val queryParams: List<String>,
        val queryParamDetails: List<EndpointDocDetails.QueryParamDetails>,
        val headerParams: List<String>,
        val fieldParams: List<String>,
        val partParams: List<String>,
        val hasDynamicUrl: Boolean,
        val hasBody: Boolean,
        val staticHeaders: List<String>
    )
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
