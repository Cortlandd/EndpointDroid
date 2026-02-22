package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Resolves method-level Retrofit metadata for a selected endpoint.
 *
 * This is intentionally best-effort; unresolved data produces empty details.
 */
internal object EndpointDocDetailsResolver {
    private const val RETROFIT_PREFIX = "retrofit2.http."
    private const val AUTHORIZATION_HEADER = "Authorization"

    /**
     * Resolves rich details for an endpoint's source method.
     */
    fun resolve(project: Project, endpoint: Endpoint): EndpointDocDetails {
        val method = findEndpointMethod(project, endpoint) ?: return EndpointDocDetails.empty()
        val source = resolveSourceLocation(project, method)

        val pathParams = mutableListOf<String>()
        val queryParams = mutableListOf<String>()
        var hasQueryMap = false
        val headerParams = mutableListOf<String>()
        var hasHeaderMap = false
        val fieldParams = mutableListOf<String>()
        var hasFieldMap = false
        val partParams = mutableListOf<String>()
        var hasPartMap = false
        var hasDynamicUrl = false
        var hasBody = false

        method.parameterList.parameters.forEach { param ->
            param.annotations.forEach { ann ->
                when (ann.qualifiedName) {
                    "retrofit2.http.Path",
                    "retrofit2.http.Param" -> pathParams += annotationNameOrFallback(ann, param.name ?: "path")

                    "retrofit2.http.Query" -> queryParams += annotationNameOrFallback(ann, param.name ?: "query")
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

        return EndpointDocDetails(
            sourceFile = source.file,
            sourceLine = source.line,
            pathParams = pathParams.distinct(),
            queryParams = queryParams.distinct(),
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
            authRequirement = authRequirement
        )
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
