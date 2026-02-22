package com.cortlandwalker.endpointdroid.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers and creates the EndpointDroid tool window content.
 */
class EndpointDroidToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Keep EndpointDroid as a full-width bottom tool window (Build/Logcat style).
        toolWindow.setAnchor(ToolWindowAnchor.BOTTOM, null)
        val panel = EndpointDroidPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
