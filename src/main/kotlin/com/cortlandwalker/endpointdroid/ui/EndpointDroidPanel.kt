package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.cortlandwalker.endpointdroid.services.EndpointService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel

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

    private val endpointList = JBList<Endpoint>().apply {
        cellRenderer = EndpointListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = INITIAL_MESSAGE
    }
    private val detailsPane = JEditorPane("text/html", "").apply {
        isEditable = false
    }

    private val endpointService = EndpointService.getInstance(project)

    init {
        // Tool window toolbar actions.
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh", "Re-scan endpoints", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    // Always give immediate feedback, then wait for smart mode before scanning indices.
                    showDetailsMessage(INDEXING_MESSAGE)
                    DumbService.getInstance(project).runWhenSmart {
                        ApplicationManager.getApplication().invokeLater {
                            refreshFromService(selectFirst = false)
                        }
                    }
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
            JBScrollPane(detailsPane)
        ).apply {
            resizeWeight = 0.45
        }
        add(split, BorderLayout.CENTER)

        // When user selects an endpoint, render docs in the right pane.
        endpointList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val ep = endpointList.selectedValue ?: return@addListSelectionListener
            renderMarkdownDetails(MarkdownDocRenderer.render(ep))
        }

        showDetailsMessage(INITIAL_MESSAGE)
    }

    /**
     * Refreshes the endpoint list from the project service and updates UI state.
     *
     * @param selectFirst if true, selects the first endpoint after refresh (useful on initial load).
     */
    private fun refreshFromService(selectFirst: Boolean) {
        val previousSelection = endpointList.selectedValue

        // Run the heavy scanning on a background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val endpoints = runCatching {
                endpointService.refresh()
                endpointService.getEndpoints()
            }.getOrElse { error ->
                ApplicationManager.getApplication().invokeLater {
                    endpointList.setListData(emptyArray())
                    showDetailsMessage("$SCAN_FAILED_PREFIX ${error.message ?: error::class.java.simpleName}")
                }
                return@executeOnPooledThread
            }

            // Switch back to UI thread to update the JList
            ApplicationManager.getApplication().invokeLater {
                endpointList.setListData(endpoints.toTypedArray())

                if (endpoints.isEmpty()) {
                    showDetailsMessage(NO_ENDPOINTS_MESSAGE)
                    return@invokeLater
                }

                val preservedIndex = previousSelection?.let { previous ->
                    endpoints.indexOfFirst {
                        it.httpMethod == previous.httpMethod &&
                            it.path == previous.path &&
                            it.serviceFqn == previous.serviceFqn &&
                            it.functionName == previous.functionName
                    }
                } ?: -1

                when {
                    preservedIndex >= 0 -> endpointList.selectedIndex = preservedIndex
                    selectFirst -> endpointList.selectedIndex = 0
                    else -> {
                        endpointList.clearSelection()
                        showDetailsMessage(SELECT_ENDPOINT_MESSAGE)
                    }
                }
            }
        }
    }

    /**
     * Renders markdown in the details pane.
     */
    private fun renderMarkdownDetails(markdown: String) {
        detailsPane.text = MarkdownHtmlRenderer.toHtml(markdown)
        detailsPane.caretPosition = 0
    }

    /**
     * Shows non-endpoint information in the details pane and resets scroll position.
     */
    private fun showDetailsMessage(message: String) {
        renderMarkdownDetails(message)
    }

    private companion object {
        const val INITIAL_MESSAGE = "Press Refresh to scan endpoints."
        const val INDEXING_MESSAGE = "Indexing..."
        const val NO_ENDPOINTS_MESSAGE = "No endpoints found."
        const val SELECT_ENDPOINT_MESSAGE = "Select an endpoint to view details."
        const val SCAN_FAILED_PREFIX = "Endpoint scan failed:"
    }
}
