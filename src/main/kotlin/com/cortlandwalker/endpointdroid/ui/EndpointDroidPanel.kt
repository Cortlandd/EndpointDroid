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
import java.awt.Dimension
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private val splitPane = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        JBScrollPane(endpointList),
        JBScrollPane(detailsPane)
    ).apply {
        resizeWeight = DEFAULT_SPLIT_WEIGHT
        isOneTouchExpandable = true
        leftComponent.minimumSize = Dimension(0, 0)
        rightComponent.minimumSize = Dimension(0, 0)
    }

    private val endpointService = EndpointService.getInstance(project)
    private var isDetailsFocused = false

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
            add(object : DumbAwareAction("Toggle Details Focus", "Expand/collapse details panel", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    toggleDetailsFocus()
                }
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actions, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        // Two-pane split: endpoints list (left) and docs (right).
        add(splitPane, BorderLayout.CENTER)

        // When user selects an endpoint, render docs in the right pane.
        endpointList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val ep = endpointList.selectedValue ?: return@addListSelectionListener
            renderMarkdownDetails(MarkdownDocRenderer.render(ep))
        }
        detailsPane.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!isDetailsFocused) {
                    maximizeDetailsPane()
                }
            }
        })

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
     * Toggles between the normal split layout and full-width details focus mode.
     */
    private fun toggleDetailsFocus() {
        if (isDetailsFocused) {
            restoreSplitPane()
        } else {
            maximizeDetailsPane()
        }
    }

    /**
     * Expands the documentation pane to full width for focused reading.
     */
    private fun maximizeDetailsPane() {
        isDetailsFocused = true
        splitPane.dividerSize = 0
        splitPane.dividerLocation = 0
    }

    /**
     * Restores the two-pane layout after details focus mode.
     */
    private fun restoreSplitPane() {
        isDetailsFocused = false
        splitPane.dividerSize = DEFAULT_DIVIDER_SIZE
        splitPane.setDividerLocation(DEFAULT_SPLIT_WEIGHT)
    }

    /**
     * Shows non-endpoint information in the details pane and resets scroll position.
     */
    private fun showDetailsMessage(message: String) {
        renderMarkdownDetails(message)
    }

    private companion object {
        const val DEFAULT_SPLIT_WEIGHT = 0.45
        const val DEFAULT_DIVIDER_SIZE = 8
        const val INITIAL_MESSAGE = "Press Refresh to scan endpoints."
        const val INDEXING_MESSAGE = "Indexing..."
        const val NO_ENDPOINTS_MESSAGE = "No endpoints found."
        const val SELECT_ENDPOINT_MESSAGE = "Select an endpoint to view details."
        const val SCAN_FAILED_PREFIX = "Endpoint scan failed:"
    }
}
