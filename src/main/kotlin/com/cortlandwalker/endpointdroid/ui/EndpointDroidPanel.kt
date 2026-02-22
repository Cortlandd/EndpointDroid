package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JSplitPane

class EndpointDroidPanel : JPanel(BorderLayout()) {

    private val endpointList = JBList<Endpoint>()
    private val detailsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        // Toolbar
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh", "Re-scan endpoints", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    loadMockEndpoints()
                }
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actions, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        // Split view
        val listScroll = JBScrollPane(endpointList)
        val detailsScroll = JBScrollPane(detailsArea)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailsScroll).apply {
            resizeWeight = 0.45
        }
        add(split, BorderLayout.CENTER)

        endpointList.addListSelectionListener {
            val ep = endpointList.selectedValue ?: return@addListSelectionListener
            detailsArea.text = MarkdownDocRenderer.render(ep)
            detailsArea.caretPosition = 0
        }

        // initial state
        loadMockEndpoints()
    }

    private fun loadMockEndpoints() {
        val endpoints = listOf(
            Endpoint(
                httpMethod = "GET",
                path = "/v1/users/{id}",
                serviceFqn = "com.example.api.UserService",
                functionName = "getUser",
                requestType = null,
                responseType = "UserResponse",
                baseUrl = "https://api.example.com"
            ),
            Endpoint(
                httpMethod = "POST",
                path = "/v1/auth/login",
                serviceFqn = "com.example.api.AuthService",
                functionName = "login",
                requestType = "LoginRequest",
                responseType = "TokenResponse",
                baseUrl = "https://api.example.com"
            )
        )

        endpointList.setListData(endpoints.toTypedArray())
        if (endpoints.isNotEmpty()) {
            endpointList.selectedIndex = 0
        } else {
            detailsArea.text = ""
        }
    }
}