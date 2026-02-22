package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

/**
 * Custom list renderer that makes endpoint rows readable at a glance:
 * path + colored method + short service name.
 */
internal class EndpointListCellRenderer : ColoredListCellRenderer<Endpoint>() {

    override fun customizeCellRenderer(
        list: JList<out Endpoint>,
        value: Endpoint?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        if (value == null) return

        append(value.path, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append("[${value.httpMethod.uppercase()}]", methodAttributes(value.httpMethod))
        append("    ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(shortServiceName(value.serviceFqn), SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun shortServiceName(serviceFqn: String): String {
        return serviceFqn.substringAfterLast('.')
    }

    private fun methodAttributes(method: String): SimpleTextAttributes {
        val color = when (method.uppercase()) {
            "GET" -> JBColor(0x4A9C5A, 0x5FBF73)
            "POST" -> JBColor(0x2B79C2, 0x4DA3FF)
            "PUT" -> JBColor(0x8A6B31, 0xD4A64D)
            "PATCH" -> JBColor(0x7B5FA6, 0xB38AE3)
            "DELETE" -> JBColor(0xB54A4A, 0xF06A6A)
            else -> JBColor(0x8C8F96, 0xAEB4BE)
        }
        return SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
    }
}
