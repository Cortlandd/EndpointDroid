package com.cortlandwalker.endpointdroid.ui

import com.intellij.ui.components.JBLabel
import javax.swing.JPanel
import java.awt.BorderLayout

class EndpointDroidPanel : JPanel(BorderLayout()) {
    init {
        add(JBLabel("Hello from EndpointDroid 👀"), BorderLayout.NORTH)
    }
}