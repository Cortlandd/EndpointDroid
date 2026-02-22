package com.cortlandwalker.endpointdroid.services

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.net.URI

/**
 * Scans project source for OkHttp endpoint patterns.
 *
 * Strategy:
 * 1. UAST-first extraction for direct OkHttp usage (`Request.Builder`, `url`, HTTP verb calls).
 * 2. Generic text heuristics as fallback for wrapper abstractions operating on `Request.Builder`.
 *
 * This keeps detection broad (custom wrappers + standard usage) without tying behavior
 * to project-specific base classes.
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

    private val kotlinBuilderMethodRegex = Regex(
        """fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*Request\.Builder[^)]*)\)\s*\{([\s\S]{0,1200}?)\n\s*\}"""
    )
    private val javaBuilderMethodRegex = Regex(
        """[A-Za-z_][A-Za-z0-9_<>,.?\[\]\s]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*Request\.Builder[^)]*)\)\s*\{([\s\S]{0,1200}?)\n\s*\}"""
    )
    private val classHeaderRegex = Regex("""class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
    private val constructorParamRegex = Regex("""(?:val|var)?\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^=,\n)]+)""")

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
            if (!looksRelevantToOkHttp(text)) return@iterateContent true

            val packageName = packageRegex.find(text)?.groupValues?.get(1).orEmpty()
            val constants = collectStringConstants(text)
            val contextIndex = SourceContextIndex.build(file.name, packageName, text)

            scanWithUast(
                project = project,
                file = file,
                packageName = packageName,
                constants = constants,
                baseUrlFallback = baseUrlFallback,
                seenKeys = seenKeys,
                results = results
            )

            scanInlineBuilderChains(
                text = text,
                constants = constants,
                contextIndex = contextIndex,
                baseUrlFallback = baseUrlFallback,
                seenKeys = seenKeys,
                results = results
            )

            scanBuilderWrapperMethods(
                text = text,
                packageName = packageName,
                contextIndex = contextIndex,
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
     * UAST-first pass for direct OkHttp usage.
     */
    private fun scanWithUast(
        project: Project,
        file: VirtualFile,
        packageName: String,
        constants: Map<String, String>,
        baseUrlFallback: String?,
        seenKeys: MutableSet<String>,
        results: MutableList<Endpoint>
    ) {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
        val uFile = psiFile.toUElementOfType<UFile>() ?: return
        val fileBaseName = file.name.substringBeforeLast('.')

        uFile.accept(object : AbstractUastVisitor() {
            override fun visitMethod(node: UMethod): Boolean {
                val sourceText = node.sourcePsi?.text ?: return super.visitMethod(node)
                val signals = MethodSignals()

                node.uastBody?.accept(object : AbstractUastVisitor() {
                    override fun visitCallExpression(call: UCallExpression): Boolean {
                        collectOkHttpCallSignals(call, signals)
                        return super.visitCallExpression(call)
                    }
                })

                val httpMethod = signals.httpMethod ?: extractHttpMethod(sourceText)
                if (httpMethod == null) return super.visitMethod(node)
                if (!signals.sawOkHttpBuilderCall && !sourceText.contains("Request.Builder")) {
                    return super.visitMethod(node)
                }

                val resolvedUrl = signals.urlExpression
                    ?.let { resolveUrlExpression(it, constants, baseUrlFallback) }
                    ?: inferUrlFromParameters(
                        signatureText = node.uastParameters.joinToString(",") { "${it.name}:${it.type.presentableText}" },
                        fallbackText = sourceText,
                        baseUrlFallback = baseUrlFallback
                    )
                    ?: return super.visitMethod(node)

                val psiMethod = node.javaPsi
                val serviceFqn = psiMethod.containingClass?.qualifiedName
                    ?: if (packageName.isBlank()) fileBaseName else "$packageName.$fileBaseName"
                val functionName = psiMethod.name.ifBlank { node.name }

                val endpoint = Endpoint(
                    httpMethod = httpMethod,
                    path = resolvedUrl.path,
                    serviceFqn = serviceFqn,
                    functionName = functionName,
                    requestType = signals.requestType ?: inferRequestType(sourceText),
                    responseType = normalizeDeclaredType(psiMethod.returnType?.presentableText),
                    baseUrl = resolvedUrl.baseUrl ?: baseUrlFallback
                )
                addUniqueEndpoint(endpoint, seenKeys, results)
                return super.visitMethod(node)
            }
        })
    }

    /**
     * Collects direct `Request.Builder().url(...).verb(...).build()` patterns.
     */
    private fun scanInlineBuilderChains(
        text: String,
        constants: Map<String, String>,
        contextIndex: SourceContextIndex,
        baseUrlFallback: String?,
        seenKeys: MutableSet<String>,
        results: MutableList<Endpoint>
    ) {
        requestBuilderBlockRegex.findAll(text).forEach { blockMatch ->
            val block = blockMatch.value
            val urlExpression = urlCallRegex.find(block)?.groupValues?.get(1)?.trim() ?: return@forEach
            val resolvedUrl = resolveUrlExpression(urlExpression, constants, baseUrlFallback)
            val context = contextIndex.contextForOffset(blockMatch.range.first)

            val endpoint = Endpoint(
                httpMethod = extractHttpMethod(block) ?: return@forEach,
                path = resolvedUrl.path,
                serviceFqn = context.serviceFqn,
                functionName = context.functionName,
                requestType = inferRequestType(block),
                responseType = normalizeDeclaredType(context.declaredResponseType),
                baseUrl = resolvedUrl.baseUrl ?: baseUrlFallback
            )
            addUniqueEndpoint(endpoint, seenKeys, results)
        }
    }

    /**
     * Generic wrapper fallback for methods operating on `Request.Builder`.
     */
    private fun scanBuilderWrapperMethods(
        text: String,
        packageName: String,
        contextIndex: SourceContextIndex,
        constants: Map<String, String>,
        baseUrlFallback: String?,
        seenKeys: MutableSet<String>,
        results: MutableList<Endpoint>
    ) {
        val classHeaders = classHeaderRegex.findAll(text)
            .map { match ->
                ClassHeader(
                    offset = match.range.first,
                    className = match.groupValues[1].trim(),
                    constructorParams = match.groupValues[2]
                )
            }
            .toList()

        fun processMatch(methodName: String, signatureParams: String, methodBody: String, offset: Int) {
            val httpMethod = extractHttpMethod(methodBody) ?: return
            val urlExpression = urlCallRegex.find(methodBody)?.groupValues?.get(1)?.trim()

            val classHeader = classHeaders.lastOrNull { it.offset <= offset }
            val resolvedUrl = if (urlExpression != null) {
                resolveUrlExpression(urlExpression, constants, baseUrlFallback)
            } else {
                inferUrlFromParameters(
                    signatureText = signatureParams,
                    fallbackText = classHeader?.constructorParams.orEmpty(),
                    baseUrlFallback = baseUrlFallback
                ) ?: return
            }

            val serviceFqn = classHeader?.let {
                if (packageName.isBlank()) it.className else "$packageName.${it.className}"
            } ?: contextIndex.contextForOffset(offset).serviceFqn

            val requestType = inferRequestType(methodBody)
                ?: extractRequestBodyType(signatureParams)
                ?: classHeader?.constructorParams?.let(::extractRequestBodyType)

            val endpoint = Endpoint(
                httpMethod = httpMethod,
                path = resolvedUrl.path,
                serviceFqn = serviceFqn,
                functionName = methodName,
                requestType = requestType,
                responseType = null,
                baseUrl = resolvedUrl.baseUrl ?: baseUrlFallback
            )
            addUniqueEndpoint(endpoint, seenKeys, results)
        }

        kotlinBuilderMethodRegex.findAll(text).forEach { match ->
            processMatch(
                methodName = match.groupValues[1].trim(),
                signatureParams = match.groupValues[2],
                methodBody = match.groupValues[3],
                offset = match.range.first
            )
        }

        javaBuilderMethodRegex.findAll(text).forEach { match ->
            processMatch(
                methodName = match.groupValues[1].trim(),
                signatureParams = match.groupValues[2],
                methodBody = match.groupValues[3],
                offset = match.range.first
            )
        }
    }

    /**
     * Collects method/url/request-type signals from resolved UAST calls.
     */
    private fun collectOkHttpCallSignals(call: UCallExpression, signals: MethodSignals) {
        val resolved = call.resolve() ?: return
        val ownerFqn = resolved.containingClass?.qualifiedName ?: return
        if (ownerFqn != OKHTTP_REQUEST_BUILDER_FQN && ownerFqn != OKHTTP_REQUEST_BUILDER_NESTED_FQN) return

        val methodName = call.methodName?.lowercase() ?: return
        signals.sawOkHttpBuilderCall = true

        when (methodName) {
            "url" -> {
                val candidate = call.valueArguments.firstOrNull()?.asSourceString()?.trim()
                if (!candidate.isNullOrBlank() && signals.urlExpression == null) {
                    signals.urlExpression = candidate
                }
            }

            "get", "post", "put", "patch", "delete", "head" -> {
                signals.httpMethod = methodName.uppercase()
                if (signals.httpMethod in bodyMethods) {
                    val bodyType = call.valueArguments.firstOrNull()?.asSourceString()
                    if (!bodyType.isNullOrBlank()) {
                        signals.requestType = normalizeDeclaredType(bodyType) ?: bodyType
                    }
                }
            }

            "method" -> {
                val methodArg = call.valueArguments.firstOrNull()?.asSourceString()?.trim()
                val explicitMethod = extractStringLiteral(methodArg.orEmpty())
                if (!explicitMethod.isNullOrBlank()) {
                    signals.httpMethod = explicitMethod.uppercase()
                }

                val bodyType = call.valueArguments.getOrNull(1)?.asSourceString()
                if (!bodyType.isNullOrBlank() && !bodyType.equals("null", ignoreCase = true)) {
                    signals.requestType = normalizeDeclaredType(bodyType) ?: bodyType
                }
            }
        }
    }

    /**
     * Determines HTTP method from request-builder method bodies/chains.
     */
    private fun extractHttpMethod(blockText: String): String? {
        methodOverrideRegex.find(blockText)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it.uppercase()
        }
        verbCallRegex.find(blockText)?.groupValues?.getOrNull(1)?.let { return it.uppercase() }
        return null
    }

    /**
     * Infers request body type from builder usage.
     */
    private fun inferRequestType(blockText: String): String? {
        if (blockText.contains("MultipartBody.Builder")) return "MultipartBody"
        if (blockText.contains("FormBody.Builder")) return "FormBody"
        if (blockText.contains("RequestBody.create") || blockText.contains("toRequestBody(")) return "RequestBody"
        return if (verbCallRegex.containsMatchIn(blockText) && (extractHttpMethod(blockText) in bodyMethods)) {
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
     * Tries to derive a URL placeholder path from method/constructor parameter names.
     */
    private fun inferUrlFromParameters(
        signatureText: String,
        fallbackText: String,
        baseUrlFallback: String?
    ): ResolvedUrl? {
        val name = findUrlLikeParameterName(signatureText) ?: findUrlLikeParameterName(fallbackText) ?: return null
        return ResolvedUrl(path = ensureLeadingSlash("{$name}"), baseUrl = baseUrlFallback)
    }

    /**
     * Finds string-like parameter names that imply URL/path content.
     */
    private fun findUrlLikeParameterName(paramText: String): String? {
        constructorParamRegex.findAll(paramText).forEach { match ->
            val name = match.groupValues[1].trim()
            val typeText = match.groupValues[2].trim()
            if (name.isBlank() || typeText.isBlank()) return@forEach
            if (!typeText.contains("String")) return@forEach
            val normalized = name.lowercase()
            if (urlLikeNames.any { normalized.contains(it) }) {
                return name
            }
        }
        return null
    }

    /**
     * Extracts request body type hint from parameter declarations.
     */
    private fun extractRequestBodyType(paramText: String): String? {
        constructorParamRegex.findAll(paramText).forEach { match ->
            val typeText = match.groupValues[2].trim().removeSuffix("?")
            if (typeText.contains("RequestBody")) {
                return typeText.substringAfterLast('.')
            }
            if (typeText.contains("MultipartBody")) {
                return "MultipartBody"
            }
            if (typeText.contains("FormBody")) {
                return "FormBody"
            }
        }
        return null
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
     * Adds endpoint if unique by stable identity key.
     */
    private fun addUniqueEndpoint(endpoint: Endpoint, seenKeys: MutableSet<String>, results: MutableList<Endpoint>) {
        val key = "${endpoint.httpMethod}:${endpoint.serviceFqn}:${endpoint.functionName}:${endpoint.path}"
        if (seenKeys.add(key)) {
            results += endpoint
        }
    }

    /**
     * Restricts scanning to source files and skips obviously irrelevant text.
     */
    private fun looksRelevantToOkHttp(text: String): Boolean {
        return text.contains("okhttp3") || text.contains("Request.Builder") || text.contains("newCall(")
    }

    /**
     * Restricts text scanning to source files supported by this parser.
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

    private data class ClassHeader(
        val offset: Int,
        val className: String,
        val constructorParams: String
    )

    private data class MethodSignals(
        var sawOkHttpBuilderCall: Boolean = false,
        var httpMethod: String? = null,
        var urlExpression: String? = null,
        var requestType: String? = null
    )

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
    private val urlLikeNames = setOf("url", "uri", "path", "endpoint", "route")
    private val javaControlKeywords = setOf("if", "for", "while", "switch", "catch", "return")
    private const val OKHTTP_REQUEST_BUILDER_FQN = "okhttp3.Request.Builder"
    private const val OKHTTP_REQUEST_BUILDER_NESTED_FQN = "okhttp3.Request\$Builder"
}
