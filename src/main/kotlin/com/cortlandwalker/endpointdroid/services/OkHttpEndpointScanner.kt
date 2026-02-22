package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import java.net.URI

/**
 * Scans project source for OkHttp request-builder call sites and extracts endpoint metadata.
 *
 * This scanner is intentionally heuristic-based so it can discover endpoints in both Kotlin and
 * Java source without depending on language-specific PSI plugins.
 */
internal object OkHttpEndpointScanner {

    private val packageRegex = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    private val classRegex = Regex(
        """(?m)^\s*(?:@[^\n]+\s*)*(?:(?:public|private|protected|internal|abstract|final|open|sealed|data|enum|annotation|static)\s+)*(?:class|interface|object|enum\s+class)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )
    private val kotlinFunctionRegex = Regex(
        """(?m)^\s*(?:@[^\n]+\s*)*(?:(?:public|private|protected|internal|suspend|inline|override|open|abstract|tailrec|operator|infix|external|final|actual|expect)\s+)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*(?::\s*([^{=\n]+))?"""
    )
    private val javaMethodRegex = Regex(
        """(?m)^\s*(?:@[^\n]+\s*)*(?:(?:public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\s+)*([A-Za-z_][A-Za-z0-9_<>,.?\[\]\s]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;\n{]*\)\s*(?:throws[^{\n]+)?\{"""
    )

    private val requestBuilderBlockRegex = Regex(
        """Request\s*\.\s*Builder\s*\(\s*\)([\s\S]{0,2500}?)\.build\s*\(\s*\)"""
    )
    private val urlCallRegex = Regex("""\.url\s*\(\s*([^)]+?)\s*\)""")
    private val methodOverrideRegex = Regex("""\.method\s*\(\s*"([A-Za-z]+)"\s*,\s*([^)]+)\)""")
    private val verbCallRegex = Regex("""\.(get|post|put|patch|delete|head)\s*\(""", RegexOption.IGNORE_CASE)
    private val okHttpMethodBaseClassRegex = Regex(
        """class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([\s\S]*?)\)\s*:\s*OkHttpMethodBase\s*\(([^)]*)\)\s*\{([\s\S]*?)\n\}"""
    )
    private val applyTypeRegex = Regex(
        """override\s+fun\s+applyType\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*:\s*Request\.Builder\s*\)\s*\{([\s\S]*?)\}"""
    )
    private val constructorParamRegex = Regex(
        """(?:val|var)?\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^=,\n)]+)"""
    )
    private val kotlinStringConstantRegex =
        Regex("(?:const\\s+)?val\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]+)\"")
    private val javaStringConstantRegex =
        Regex("(?:public\\s+|private\\s+|protected\\s+)?(?:static\\s+final\\s+)?String\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]+)\"")
    private val concatSplitRegex = Regex("""\s*\+\s*""")

    /**
     * Returns discovered OkHttp endpoints for the current project.
     */
    fun scan(project: Project): List<Endpoint> {
        val baseUrlFallback = BaseUrlResolver.resolve(project)
        val results = mutableListOf<Endpoint>()
        val seenKeys = HashSet<String>()

        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!isSupportedSourceFile(file.name)) return@iterateContent true

            val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@iterateContent true
            if (!text.contains("Request.Builder")) return@iterateContent true

            val packageName = packageRegex.find(text)?.groupValues?.get(1).orEmpty()
            val constants = collectStringConstants(text)
            val contextIndex = SourceContextIndex.build(file.name, packageName, text)

            requestBuilderBlockRegex.findAll(text).forEach { blockMatch ->
                val block = blockMatch.value
                val urlExpression = urlCallRegex.find(block)?.groupValues?.get(1)?.trim() ?: return@forEach
                val resolvedUrl = resolveUrlExpression(urlExpression, constants, baseUrlFallback)
                val context = contextIndex.contextForOffset(blockMatch.range.first)
                val httpMethod = extractHttpMethod(block)
                val requestType = inferRequestType(block)
                val responseType = normalizeDeclaredType(context.declaredResponseType)

                val endpoint = Endpoint(
                    httpMethod = httpMethod,
                    path = resolvedUrl.path,
                    serviceFqn = context.serviceFqn,
                    functionName = context.functionName,
                    requestType = requestType,
                    responseType = responseType,
                    baseUrl = resolvedUrl.baseUrl ?: baseUrlFallback
                )

                val key = "${endpoint.httpMethod}:${endpoint.serviceFqn}:${endpoint.functionName}:${endpoint.path}"
                if (seenKeys.add(key)) {
                    results += endpoint
                }
            }

            scanOkHttpMethodBaseSubclasses(
                text = text,
                packageName = packageName,
                constants = constants,
                baseUrlFallback = baseUrlFallback,
                seenKeys = seenKeys,
                results = results
            )
            true
        }

        return results.sortedWith(compareBy({ it.serviceFqn }, { it.path }, { it.functionName }))
    }

    /**
     * Determines HTTP method from builder chain calls.
     */
    private fun extractHttpMethod(blockText: String): String {
        methodOverrideRegex.find(blockText)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it.uppercase()
        }
        verbCallRegex.find(blockText)?.groupValues?.getOrNull(1)?.let { return it.uppercase() }
        return "GET"
    }

    /**
     * Infers request body type from request builder usage.
     */
    private fun inferRequestType(blockText: String): String? {
        if (blockText.contains("MultipartBody.Builder")) return "MultipartBody"
        if (blockText.contains("FormBody.Builder")) return "FormBody"
        if (blockText.contains("RequestBody.create") || blockText.contains("toRequestBody(")) return "RequestBody"
        return if (verbCallRegex.containsMatchIn(blockText) && extractHttpMethod(blockText) in bodyMethods) {
            "RequestBody"
        } else {
            null
        }
    }

    /**
     * Resolves URL/path from `.url(...)` expression using local constants when possible.
     */
    private fun resolveUrlExpression(
        expression: String,
        constants: Map<String, String>,
        baseUrlFallback: String?
    ): ResolvedUrl {
        val rawExpr = expression
            .removeSuffix(".toHttpUrl()")
            .removeSuffix(".toHttpUrlOrNull()")
            .trim()

        val directLiteral = extractStringLiteral(rawExpr)
        if (directLiteral != null) {
            return normalizeUrlCandidate(toEndpointPlaceholders(directLiteral), baseUrlFallback)
        }

        val concatenated = concatSplitRegex.split(rawExpr)
            .mapNotNull { token ->
                val cleaned = token.trim().removePrefix("(").removeSuffix(")")
                extractStringLiteral(cleaned)
                    ?: constants[cleaned]
                    ?: constants[cleaned.substringAfterLast('.')]
                    ?: tokenToPlaceholder(cleaned)
            }
            .joinToString("")
            .trim()

        if (concatenated.isNotEmpty()) {
            return normalizeUrlCandidate(toEndpointPlaceholders(concatenated), baseUrlFallback)
        }

        return ResolvedUrl(path = "/", baseUrl = baseUrlFallback)
    }

    /**
     * Converts raw URL expression output into normalized endpoint path and base URL.
     */
    private fun normalizeUrlCandidate(rawValue: String, baseUrlFallback: String?): ResolvedUrl {
        val value = rawValue.trim()
        if (value.isEmpty()) return ResolvedUrl(path = "/", baseUrl = baseUrlFallback)

        if (value.startsWith("http://") || value.startsWith("https://")) {
            val uri = runCatching { URI(value) }.getOrNull()
            if (uri != null && uri.scheme != null && uri.host != null) {
                val rawPath = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
                val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
                val normalizedPath = ensureLeadingSlash("$rawPath$query")
                val absoluteBase = "${uri.scheme}://${uri.authority}".trimEnd('/')
                return ResolvedUrl(path = normalizedPath, baseUrl = absoluteBase)
            }
        }

        return ResolvedUrl(
            path = ensureLeadingSlash(value),
            baseUrl = baseUrlFallback
        )
    }

    /**
     * Captures simple string constants from Kotlin and Java source.
     */
    private fun collectStringConstants(text: String): Map<String, String> {
        val constants = linkedMapOf<String, String>()
        kotlinStringConstantRegex.findAll(text).forEach { match ->
            constants.putIfAbsent(match.groupValues[1], match.groupValues[2])
        }
        javaStringConstantRegex.findAll(text).forEach { match ->
            constants.putIfAbsent(match.groupValues[1], match.groupValues[2])
        }
        return constants
    }

    /**
     * Returns unquoted content if expression is a string literal.
     */
    private fun extractStringLiteral(expression: String): String? {
        val trimmed = expression.trim()
        if (trimmed.length < 2) return null
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return null
        return trimmed.removeSurrounding("\"")
    }

    /**
     * Converts unresolved expression tokens into placeholder segments.
     */
    private fun tokenToPlaceholder(token: String): String? {
        val clean = token.trim()
        if (clean.isEmpty()) return null
        if (!clean.matches(Regex("""[A-Za-z_][A-Za-z0-9_.]*"""))) return null
        return "{${clean.substringAfterLast('.')}}"
    }

    /**
     * Normalizes Kotlin string-template markers into endpoint placeholder syntax.
     */
    private fun toEndpointPlaceholders(value: String): String {
        var normalized = Regex("""\$\{([^}]+)}""").replace(value) { match ->
            val key = match.groupValues[1].trim().substringAfterLast('.')
            "{${key.ifBlank { "value" }}}"
        }
        normalized = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""").replace(normalized) { match ->
            "{${match.groupValues[1]}}"
        }
        return normalized
    }

    /**
     * Ensures relative paths are rendered with a leading slash.
     */
    private fun ensureLeadingSlash(path: String): String {
        if (path.isBlank()) return "/"
        return if (path.startsWith("/")) path else "/$path"
    }

    /**
     * Unwraps common wrapper types to present actual response payload types.
     */
    private fun normalizeDeclaredType(typeText: String?): String? {
        val raw = typeText?.trim()?.removeSuffix("?")?.takeIf { it.isNotBlank() } ?: return null
        val callMatch = Regex("""(?:Call|Response|retrofit2\.Call|retrofit2\.Response)<(.+)>""").matchEntire(raw)
        if (callMatch != null) return callMatch.groupValues[1].trim()
        return raw
    }

    /**
     * Extracts endpoint methods from wrapper classes that extend `OkHttpMethodBase`.
     *
     * Libraries like Nextcloud model HTTP verbs in subclasses whose `applyType(...)` method
     * sets the request method (`temp.get()`, `temp.post(body)`, etc.) while URL is passed
     * through constructor argument (`uri`), not inline `Request.Builder().url(...)` chains.
     */
    private fun scanOkHttpMethodBaseSubclasses(
        text: String,
        packageName: String,
        constants: Map<String, String>,
        baseUrlFallback: String?,
        seenKeys: MutableSet<String>,
        results: MutableList<Endpoint>
    ) {
        okHttpMethodBaseClassRegex.findAll(text).forEach { classMatch ->
            val className = classMatch.groupValues[1].trim()
            val constructorParamsRaw = classMatch.groupValues[2]
            val superArgsRaw = classMatch.groupValues[3]
            val classBody = classMatch.groupValues[4]

            val applyTypeBody = applyTypeRegex.find(classBody)?.groupValues?.getOrNull(1)?.trim()
                ?: return@forEach

            val httpMethod = extractHttpMethod(applyTypeBody)
            val requestType = inferRequestType(applyTypeBody)

            val constructorParamTypes = constructorParamRegex.findAll(constructorParamsRaw)
                .associate { match ->
                    match.groupValues[1].trim() to match.groupValues[2].trim()
                }

            val uriExpression = superArgsRaw.substringBefore(',').trim()
            val resolvedUrl = resolveUrlExpression(
                expression = uriExpression,
                constants = constants,
                baseUrlFallback = baseUrlFallback
            )

            val endpoint = Endpoint(
                httpMethod = httpMethod,
                path = resolvedUrl.path,
                serviceFqn = if (packageName.isBlank()) className else "$packageName.$className",
                functionName = "applyType",
                requestType = requestType
                    ?: constructorParamTypes.values.firstOrNull { it.contains("RequestBody") }?.substringAfterLast('.'),
                responseType = null,
                baseUrl = resolvedUrl.baseUrl ?: baseUrlFallback
            )

            val key = "${endpoint.httpMethod}:${endpoint.serviceFqn}:${endpoint.functionName}:${endpoint.path}"
            if (seenKeys.add(key)) {
                results += endpoint
            }
        }
    }

    /**
     * Restricts text scanning to source files supported by this heuristic parser.
     */
    private fun isSupportedSourceFile(fileName: String): Boolean {
        return fileName.endsWith(".kt") || fileName.endsWith(".java")
    }

    private data class ResolvedUrl(val path: String, val baseUrl: String?)

    private data class SourceContext(
        val serviceFqn: String,
        val functionName: String,
        val declaredResponseType: String?
    )

    private data class FunctionDecl(val offset: Int, val name: String, val returnType: String?)

    /**
     * Provides nearest class/function declaration context for endpoint call sites.
     */
    private data class SourceContextIndex(
        val fileBaseName: String,
        val packageName: String,
        val classDecls: List<Pair<Int, String>>,
        val functionDecls: List<FunctionDecl>
    ) {
        fun contextForOffset(offset: Int): SourceContext {
            val className = classDecls.lastOrNull { it.first <= offset }?.second ?: fileBaseName
            val functionDecl = functionDecls.lastOrNull { it.offset <= offset }
            val serviceFqn = if (packageName.isBlank()) className else "$packageName.$className"
            val functionName = functionDecl?.name ?: "requestAt$offset"
            return SourceContext(serviceFqn, functionName, functionDecl?.returnType)
        }

        companion object {
            fun build(fileName: String, packageName: String, text: String): SourceContextIndex {
                val fileBaseName = fileName.substringBeforeLast('.')
                val classDecls = classRegex.findAll(text)
                    .map { it.range.first to it.groupValues[1] }
                    .toList()

                val functionDecls = mutableListOf<FunctionDecl>()
                kotlinFunctionRegex.findAll(text).forEach { match ->
                    functionDecls += FunctionDecl(
                        offset = match.range.first,
                        name = match.groupValues[1],
                        returnType = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                    )
                }
                javaMethodRegex.findAll(text).forEach { match ->
                    val returnType = match.groupValues[1].trim()
                    val name = match.groupValues[2].trim()
                    if (name in javaControlKeywords) return@forEach
                    functionDecls += FunctionDecl(
                        offset = match.range.first,
                        name = name,
                        returnType = returnType
                    )
                }

                return SourceContextIndex(
                    fileBaseName = fileBaseName,
                    packageName = packageName,
                    classDecls = classDecls.sortedBy { it.first },
                    functionDecls = functionDecls.sortedBy { it.offset }
                )
            }
        }
    }

    private val bodyMethods = setOf("POST", "PUT", "PATCH", "DELETE")
    private val javaControlKeywords = setOf("if", "for", "while", "switch", "catch", "return")
}
