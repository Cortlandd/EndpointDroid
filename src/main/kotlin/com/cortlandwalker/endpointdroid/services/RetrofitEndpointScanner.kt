package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.util.Processor

/**
 * Scans a project for Retrofit service methods and extracts endpoint metadata.
 *
 * v0 approach:
 * - Uses IntelliJ PSI (Java model) + [AnnotatedElementsSearch] over Retrofit HTTP annotations.
 * - This is good enough for MVP, but not the most efficient.
 *
 * Next iterations:
 * - Replace brute-force scanning with a proper index (FileBasedIndex / StubIndex).
 * - Improve Kotlin support and return-type unwrapping (Kotlin Analysis API if needed).
 */
object RetrofitEndpointScanner {

    /**
     * Fully-qualified Retrofit HTTP annotations we support.
     */
    private val retrofitHttpAnnotations = setOf(
        "retrofit2.http.GET",
        "retrofit2.http.POST",
        "retrofit2.http.PUT",
        "retrofit2.http.PATCH",
        "retrofit2.http.DELETE",
        "retrofit2.http.HEAD",
        "retrofit2.http.OPTIONS",
        "retrofit2.http.HTTP",
    )

    /**
     * Scans the project and returns a snapshot list of discovered Retrofit endpoints.
     *
     * @param project current IDE project.
     * @return list of endpoints (may be empty).
     */
    fun scan(project: Project): List<Endpoint> {
        val results = mutableListOf<Endpoint>()
        val seenEndpointKeys = HashSet<String>()
        val scope = GlobalSearchScope.projectScope(project)
        val javaFacade = JavaPsiFacade.getInstance(project)
        val baseUrl = BaseUrlResolver.resolve(project)

        // Instead of all class names, just look for methods with Retrofit annotations
        retrofitHttpAnnotations.forEach { annotationFqn ->
            val annotationClass = javaFacade.findClass(annotationFqn, GlobalSearchScope.allScope(project))
                ?: return@forEach

            AnnotatedElementsSearch.searchPsiMethods(annotationClass, scope)
                .forEach(Processor { method ->
                    val cls = method.containingClass ?: return@Processor true
                    val httpInfo = extractHttpInfo(method) ?: return@Processor true

                    val endpoint = Endpoint(
                        httpMethod = httpInfo.method,
                        path = httpInfo.path,
                        serviceFqn = cls.qualifiedName ?: cls.name ?: "Unknown",
                        functionName = method.name,
                        requestType = extractBodyType(method),
                        responseType = extractResponseType(method),
                        baseUrl = baseUrl
                    )
                    val key = "${endpoint.httpMethod}:${endpoint.serviceFqn}:${endpoint.functionName}:${endpoint.path}"
                    if (seenEndpointKeys.add(key)) {
                        results += endpoint
                    }
                    true
                })
        }

        return results
            .sortedWith(compareBy({ it.serviceFqn }, { it.path }))
    }

    /**
     * Simple HTTP descriptor extracted from a Retrofit annotation.
     */
    private data class HttpInfo(val method: String, val path: String)

    /**
     * Extracts Retrofit HTTP method and path from a method's annotations.
     *
     * Supports:
     * - @GET("/path")
     * - @POST("path")  (normalized to "/path")
     * - @HTTP(method="PATCH", path="/path")  (custom method/path)
     */
    private fun extractHttpInfo(method: PsiMethod): HttpInfo? {
        val annotations = method.modifierList.annotations

        for (ann in annotations) {
            val qName = ann.qualifiedName ?: continue
            if (!retrofitHttpAnnotations.contains(qName)) continue

            val simpleName = qName.substringAfterLast('.')

            // Most annotations: the path is the "value" attribute.
            val rawPath = ann.findAttributeValue("value")?.text?.trim('"')
                ?: ann.parameterList.attributes.firstOrNull()?.value?.text?.trim('"')
                ?: ""

            if (simpleName == "HTTP") {
                // @HTTP(method="GET", path="/foo", hasBody=true)
                val m = ann.findAttributeValue("method")?.text?.trim('"') ?: "GET"
                val p = ann.findAttributeValue("path")?.text?.trim('"') ?: rawPath
                return HttpInfo(m, normalizePath(p))
            }

            return HttpInfo(simpleName, normalizePath(rawPath))
        }

        return null
    }

    /**
     * Normalizes a Retrofit path so the UI and export formats are consistent.
     */
    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        return if (path.startsWith("/")) path else "/$path"
    }

    /**
     * Finds the first parameter annotated with @Body and returns its presentable type name.
     *
     * v0: supports Java PSI types; Kotlin PSI improvements come later.
     */
    private fun extractBodyType(method: PsiMethod): String? {
        val params = method.parameterList.parameters
        val bodyParam = params.firstOrNull { p ->
            p.annotations.any { it.qualifiedName == "retrofit2.http.Body" }
        } ?: return null

        return bodyParam.type.presentableText
    }

    /**
     * Extracts a best-effort response type from the method return type.
     *
     * v0 behavior:
     * - Unwraps `Call<T>` and `Response<T>` when those raw types are visible in presentable text.
     * - Unwraps Kotlin suspend signatures compiled as `Object` + `Continuation<? super T>`.
     * - Otherwise returns the presentable return type.
     *
     * Notes:
     * - Kotlin suspend functions often appear as Object/Continuation at the Java PSI level.
     *   We will improve this after MVP (likely via UAST/Kotlin Analysis API).
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
     * Extracts the real Kotlin suspend return type from the trailing Continuation parameter.
     */
    private fun extractSuspendContinuationType(method: PsiMethod): String? {
        val lastParamType = method.parameterList.parameters
            .lastOrNull()
            ?.type
            ?.presentableText
            ?: return null

        val match = Regex("""(?:kotlin\.coroutines\.)?Continuation<(.+)>""")
            .matchEntire(lastParamType)
            ?: return null

        val raw = match.groupValues[1]
            .removePrefix("? super ")
            .removePrefix("? extends ")
            .trim()

        if (raw.isBlank()) return null
        return raw
    }
}
