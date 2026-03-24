package com.flutterImpactAnalyzer.listeners

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class TestAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        Messages.showInfoMessage(
            "Plugin is working!",
            "Flutter Key Impact Detector"
        )
    }
}