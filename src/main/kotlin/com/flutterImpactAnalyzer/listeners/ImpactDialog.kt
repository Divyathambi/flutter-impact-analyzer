package com.flutterImpactAnalyzer.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.flutterImpactAnalyzer.listeners.KeyUsageFinder

object ImpactDialog {

    fun show(
        project: Project,
        oldKey: String,
        newKey: String
    ) {

        val files =
            KeyUsageFinder.findFilesUsingKey(project, oldKey)

        val fileList =
            if (files.isEmpty()) "No usages found"
            else files.joinToString("\n")

        val message = """
Key changed from:

$oldKey → $newKey

Affected Files:
$fileList

Apply change?
""".trimIndent()

        val result = Messages.showYesNoDialog(
            project,
            message,
            "Flutter Key Change Detected",
            Messages.getQuestionIcon()
        )

        if (result == Messages.YES) {

            Messages.showInfoMessage(
                project,
                "You can implement auto replace here.",
                "Update Keys"
            )
        }
    }
}