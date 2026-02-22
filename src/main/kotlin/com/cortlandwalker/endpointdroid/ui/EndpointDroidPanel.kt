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

/**
 * Main UI for the EndpointDroid tool window.
 *
 * Layout:
 * - Top: tool window toolbar (Refresh now; Export later)
 * - Left: endpoint list
 * - Right: rendered endpoint documentation
 *
 * The panel does NOT own the endpoint data; it delegates to [EndpointService].
 */
class EndpointDroidPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val endpointList = JBList<Endpoint>()
    private val detailsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    private val endpointService = EndpointService.getInstance(project)

    init {
        // Tool window toolbar actions.
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh", "Re-scan endpoints", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    // Keep selection if possible; Step 3 will refresh from scanner.
                    refreshFromService(selectFirst = false)
                }
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actions, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        // Two-pane split: endpoints list (left) and docs (right).
        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(endpointList),
            JBScrollPane(detailsArea)
        ).apply {
            resizeWeight = 0.45
        }
        add(split, BorderLayout.CENTER)

        // When user selects an endpoint, render docs in the right pane.
        endpointList.addListSelectionListener {
            val ep = endpointList.selectedValue ?: return@addListSelectionListener
            detailsArea.text = MarkdownDocRenderer.render(ep)
            detailsArea.caretPosition = 0
        }

        // Initial load.
        refreshFromService(selectFirst = true)
    }

    /**
     * Refreshes the endpoint list from the project service and updates UI state.
     *
     * @param selectFirst if true, selects the first endpoint after refresh (useful on initial load).
     */
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