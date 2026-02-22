package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.cortlandwalker.endpointdroid.services.EndpointService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JSplitPane

class EndpointDroidPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val endpointList = JBList<Endpoint>()
    private val detailsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    private val endpointService = EndpointService.getInstance(project)

    init {
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh", "Re-scan endpoints", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    refreshFromService(selectFirst = false)
                }
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actions, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(endpointList),
            JBScrollPane(detailsArea)
        ).apply { resizeWeight = 0.45 }

        add(split, BorderLayout.CENTER)

        endpointList.addListSelectionListener {
            val ep = endpointList.selectedValue ?: return@addListSelectionListener
            detailsArea.text = MarkdownDocRenderer.render(ep)
            detailsArea.caretPosition = 0
        }

        refreshFromService(selectFirst = true)
    }

    private fun refreshFromService(selectFirst: Boolean) {
        endpointService.refresh()
        val endpoints = endpointService.getEndpoints()

        endpointList.setListData(endpoints.toTypedArray())

        if (endpoints.isEmpty()) {
            detailsArea.text = ""
            return
        }

        if (selectFirst) {
            endpointList.selectedIndex = 0
        }
    }
}