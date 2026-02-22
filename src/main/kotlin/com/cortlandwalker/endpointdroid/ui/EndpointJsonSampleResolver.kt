package com.cortlandwalker.endpointdroid.ui

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Produces best-effort JSON schema/example snippets from endpoint model type names.
 *
 * The output is intentionally heuristic because Kotlin light classes and Java PSI vary by project;
 * this resolver prefers "useful and stable" over "perfect and fragile".
 */
internal object EndpointJsonSampleResolver {
    private const val MAX_DEPTH = 3

    /**
     * Pair of JSON snippets rendered into the endpoint details panel.
     */
    data class JsonSamples(
        val schemaJson: String,
        val exampleJson: String
    )

    /**
     * Builds schema/example JSON snippets for a type name when resolvable.
     */
    fun build(project: Project, typeText: String?): JsonSamples? {
        val normalized = normalizeTypeText(typeText) ?: return null
        val schemaValue = buildValue(
            project = project,
            typeText = normalized,
            mode = SampleMode.SCHEMA,
            fieldNameHint = null,
            depth = 0,
            visited = mutableSetOf()
        )
        val exampleValue = buildValue(
            project = project,
            typeText = normalized,
            mode = SampleMode.EXAMPLE,
            fieldNameHint = null,
            depth = 0,
            visited = mutableSetOf()
        )
        return JsonSamples(
            schemaJson = renderJson(schemaValue, 0),
            exampleJson = renderJson(exampleValue, 0)
        )
    }

    private fun buildValue(
        project: Project,
        typeText: String,
        mode: SampleMode,
        fieldNameHint: String?,
        depth: Int,
        visited: MutableSet<String>
    ): JsonValue {
        if (depth >= MAX_DEPTH) return JsonObject(emptyList())

        val cleaned = normalizeTypeText(typeText) ?: return JsonString("string")
        val primitive = primitiveValue(cleaned, mode, fieldNameHint)
        if (primitive != null) return primitive

        val rawType = rawTypeName(cleaned)
        val genericArgs = splitGenericArguments(cleaned)
        val loweredRaw = rawType.lowercase()

        if (loweredRaw.endsWith("array") || cleaned.endsWith("[]")) {
            val arrayItemType = genericArgs.firstOrNull()
                ?: cleaned.removeSuffix("[]").trim()
                .ifBlank { "String" }
            return JsonArray(listOf(buildValue(project, arrayItemType, mode, fieldNameHint, depth + 1, visited)))
        }

        if (loweredRaw.contains("list") || loweredRaw.contains("set") || loweredRaw.contains("collection")) {
            val elementType = genericArgs.firstOrNull() ?: "String"
            return JsonArray(listOf(buildValue(project, elementType, mode, fieldNameHint, depth + 1, visited)))
        }

        if (loweredRaw.contains("map")) {
            val valueType = genericArgs.getOrNull(1) ?: genericArgs.firstOrNull() ?: "String"
            val value = buildValue(project, valueType, mode, "value", depth + 1, visited)
            return JsonObject(listOf("key" to value))
        }

        val psiClass = resolveClass(project, rawType) ?: return JsonObject(emptyList())
        val classKey = psiClass.qualifiedName ?: psiClass.name ?: rawType
        if (!visited.add(classKey)) return JsonObject(emptyList())

        if (psiClass.isEnum) {
            val enumValue = psiClass.fields
                .firstOrNull { it is com.intellij.psi.PsiEnumConstant }
                ?.name
                ?: "VALUE"
            return JsonString(if (mode == SampleMode.SCHEMA) "string" else enumValue)
        }

        val properties = extractProperties(psiClass)
        if (properties.isEmpty()) return JsonObject(emptyList())

        val objectFields = properties.map { property ->
            property.name to buildValue(
                project = project,
                typeText = property.typeText,
                mode = mode,
                fieldNameHint = property.name,
                depth = depth + 1,
                visited = visited.toMutableSet()
            )
        }
        return JsonObject(objectFields)
    }

    private fun primitiveValue(typeText: String, mode: SampleMode, fieldNameHint: String?): JsonValue? {
        val lowered = typeText.substringAfterLast('.').lowercase()
        return when {
            lowered in stringLikeTypes -> JsonString(stringSample(fieldNameHint, mode))
            lowered in integerLikeTypes -> JsonNumber(if (mode == SampleMode.SCHEMA) "0" else numberSample(fieldNameHint))
            lowered in decimalLikeTypes -> JsonNumber(if (mode == SampleMode.SCHEMA) "0.0" else "1.0")
            lowered in booleanLikeTypes -> JsonBoolean(mode == SampleMode.EXAMPLE)
            lowered == "unit" || lowered == "void" -> JsonNull
            else -> null
        }
    }

    private fun extractProperties(psiClass: PsiClass): List<PropertyDescriptor> {
        val fromFields = psiClass.fields
            .filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            .filterNot { it.name.contains('$') || it.name == "serialVersionUID" }
            .map { field -> PropertyDescriptor(field.name, field.type.presentableText) }
        if (fromFields.isNotEmpty()) return fromFields.distinctBy { it.name }

        val fromGetters = psiClass.methods
            .asSequence()
            .filter { it.containingClass == psiClass }
            .filter { method -> !method.hasModifierProperty(PsiModifier.STATIC) }
            .filter { method -> method.parameterList.parametersCount == 0 && method.returnType != null }
            .filter { method -> method.name != "getClass" }
            .mapNotNull { getterPropertyName(it)?.let { name -> PropertyDescriptor(name, it.returnType!!.presentableText) } }
            .toList()

        return fromGetters.distinctBy { it.name }
    }

    private fun getterPropertyName(method: PsiMethod): String? {
        val name = method.name
        return when {
            name.startsWith("get") && name.length > 3 ->
                name.substring(3).replaceFirstChar { it.lowercase() }

            name.startsWith("is") && name.length > 2 ->
                name.substring(2).replaceFirstChar { it.lowercase() }

            else -> null
        }
    }

    private fun resolveClass(project: Project, typeName: String): PsiClass? {
        val javaFacade = JavaPsiFacade.getInstance(project)
        val allScope = GlobalSearchScope.allScope(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val shortNameCache = PsiShortNamesCache.getInstance(project)

        val fqNameCandidate = typeName.trim()
        if (fqNameCandidate.contains('.')) {
            javaFacade.findClass(fqNameCandidate, allScope)?.let { return it }
        }

        val shortName = fqNameCandidate.substringAfterLast('.')
        val projectMatches = shortNameCache.getClassesByName(shortName, projectScope)
        if (projectMatches.isNotEmpty()) return projectMatches.first()

        val globalMatches = shortNameCache.getClassesByName(shortName, allScope)
        return globalMatches.firstOrNull()
    }

    private fun normalizeTypeText(typeText: String?): String? {
        val raw = typeText
            ?.replace("out ", "")
            ?.replace("in ", "")
            ?.removePrefix("? super ")
            ?.removePrefix("? extends ")
            ?.trim()
            ?.trimEnd('?')
        return raw?.takeIf { it.isNotBlank() }
    }

    private fun rawTypeName(typeText: String): String {
        val genericStart = typeText.indexOf('<')
        val noGenerics = if (genericStart >= 0) typeText.substring(0, genericStart) else typeText
        return noGenerics.trim().removeSuffix("?")
    }

    private fun splitGenericArguments(typeText: String): List<String> {
        val start = typeText.indexOf('<')
        if (start < 0) return emptyList()
        val end = typeText.lastIndexOf('>')
        if (end <= start) return emptyList()
        val inner = typeText.substring(start + 1, end)

        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        inner.forEach { char ->
            when (char) {
                '<' -> {
                    depth++
                    current.append(char)
                }

                '>' -> {
                    depth--
                    current.append(char)
                }

                ',' -> {
                    if (depth == 0) {
                        result += current.toString().trim()
                        current.clear()
                    } else {
                        current.append(char)
                    }
                }

                else -> current.append(char)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) result += tail
        return result
    }

    private fun numberSample(fieldNameHint: String?): String {
        val lowered = fieldNameHint?.lowercase().orEmpty()
        return if ("expire" in lowered || "ttl" in lowered || "duration" in lowered) "3600" else "1"
    }

    private fun stringSample(fieldNameHint: String?, mode: SampleMode): String {
        if (mode == SampleMode.SCHEMA) return "string"

        val lowered = fieldNameHint?.lowercase().orEmpty()
        return when {
            "email" in lowered -> "test@example.com"
            "password" in lowered || "passcode" in lowered -> "********"
            "token" in lowered -> "token_value"
            "device" in lowered && "id" in lowered -> "A1B2C3"
            lowered == "code" -> "INVALID_CREDENTIALS"
            lowered == "message" -> "string"
            lowered.endsWith("id") -> "A1B2C3"
            "url" in lowered -> "https://example.com"
            else -> "string"
        }
    }

    private fun renderJson(value: JsonValue, indent: Int): String {
        return when (value) {
            is JsonObject -> {
                if (value.fields.isEmpty()) return "{}"
                val indentText = "  ".repeat(indent)
                val childIndentText = "  ".repeat(indent + 1)
                val lines = value.fields.joinToString(",\n") { (name, child) ->
                    "$childIndentText\"${escapeJson(name)}\": ${renderJson(child, indent + 1)}"
                }
                "{\n$lines\n$indentText}"
            }

            is JsonArray -> {
                if (value.items.isEmpty()) return "[]"
                val first = value.items.first()
                "[${renderJson(first, indent)}]"
            }

            is JsonString -> "\"${escapeJson(value.value)}\""
            is JsonNumber -> value.value
            is JsonBoolean -> value.value.toString()
            JsonNull -> "null"
        }
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private data class PropertyDescriptor(
        val name: String,
        val typeText: String
    )

    private enum class SampleMode {
        SCHEMA,
        EXAMPLE
    }

    private sealed interface JsonValue
    private data class JsonObject(val fields: List<Pair<String, JsonValue>>) : JsonValue
    private data class JsonArray(val items: List<JsonValue>) : JsonValue
    private data class JsonString(val value: String) : JsonValue
    private data class JsonNumber(val value: String) : JsonValue
    private data class JsonBoolean(val value: Boolean) : JsonValue
    private object JsonNull : JsonValue

    private val stringLikeTypes = setOf("string", "charsequence", "char")
    private val integerLikeTypes = setOf("byte", "short", "int", "integer", "long")
    private val decimalLikeTypes = setOf("float", "double", "bigdecimal")
    private val booleanLikeTypes = setOf("boolean")
}
