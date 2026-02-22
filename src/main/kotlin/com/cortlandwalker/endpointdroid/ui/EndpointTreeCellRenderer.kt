package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Renders grouped endpoint rows with compact badges optimized for fast scanning.
 */
internal class EndpointTreeCellRenderer(
    private val metadataProvider: (Endpoint) -> EndpointListMetadata?
) : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val item = node.userObject) {
            is EndpointServiceGroup -> renderServiceGroup(item)
            is Endpoint -> renderEndpoint(item, selected)
            else -> append(item?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun renderServiceGroup(group: EndpointServiceGroup) {
        append(shortServiceName(group.serviceFqn), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append(" (${group.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun renderEndpoint(endpoint: Endpoint, selected: Boolean) {
        val method = endpoint.httpMethod.uppercase()
        append("[$method]", methodAttributes(method))
        val trailingPad = (METHOD_WIDTH - method.length).coerceAtLeast(0)
        if (trailingPad > 0) {
            append(" ".repeat(trailingPad), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(
            normalizeDisplayPath(endpoint.path),
            if (selected) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        )

        endpoint.responseType?.let { responseType ->
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append(responseType, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        val metadata = metadataProvider(endpoint)
        metadata?.let {
            appendBadgeIf(it.authRequirement == EndpointDocDetails.AuthRequirement.REQUIRED, "auth", BADGE_AUTH)
            appendBadgeIf(it.queryCount > 0, "?${it.queryCount}", BADGE_QUERY)
            appendBadgeIf(it.hasMultipart, "multipart", BADGE_PARTS)
            appendBadgeIf(it.hasFormFields, "form", BADGE_FORM)
            appendBadgeIf(!it.baseUrlResolved, "host?", BADGE_WARN)
            appendBadgeIf(it.partial, "partial", BADGE_WARN)
        }
    }

    private fun appendBadgeIf(show: Boolean, text: String, color: JBColor) {
        if (!show) return
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append("[$text]", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
    }

    private fun shortServiceName(serviceFqn: String): String {
        return serviceFqn.substringAfterLast('.')
    }

    private fun normalizeDisplayPath(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun methodAttributes(method: String): SimpleTextAttributes {
        val color = when (method) {
            "GET" -> JBColor(0x4A9C5A, 0x5FBF73)
            "POST" -> JBColor(0x2B79C2, 0x4DA3FF)
            "PUT" -> JBColor(0x8A6B31, 0xD4A64D)
            "PATCH" -> JBColor(0x7B5FA6, 0xB38AE3)
            "DELETE" -> JBColor(0xB54A4A, 0xF06A6A)
            else -> JBColor(0x8C8F96, 0xAEB4BE)
        }
        return SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
    }

    private companion object {
        const val METHOD_WIDTH = 6
        val BADGE_AUTH = JBColor(0xB06D00, 0xF0B04A)
        val BADGE_QUERY = JBColor(0x3A7FB3, 0x75B6F0)
        val BADGE_PARTS = JBColor(0x6D5AA8, 0xA48AE0)
        val BADGE_FORM = JBColor(0x5F8A3D, 0x8CC261)
        val BADGE_WARN = JBColor(0xB54A4A, 0xF06A6A)
    }
}
