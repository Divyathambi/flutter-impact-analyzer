package com.flutterImpactAnalyzer.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService

import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import java.util.TimerTask

class KeyChangeListener : ProjectActivity {

    private val flutterKeyRegex = Regex(
        """(Key|ValueKey)\s*\(\s*["'](.*?)["']\s*\)"""
    )

    private val previousFileContent = mutableMapOf<String, String>()
    private val debounceTimers = ConcurrentHashMap<String, Timer>()
    private val debounceDelay = 1000L

    override suspend fun execute(project: Project) {

        val editorFactory = EditorFactory.getInstance()

        editorFactory.eventMulticaster.addDocumentListener(object : DocumentListener {

            override fun documentChanged(event: DocumentEvent) {

                val document = event.document
                val file = FileDocumentManager.getInstance().getFile(document) ?: return

                if (file.extension != "dart") return

                val filePath = file.path
                val newText = document.text

                debounceTimers[filePath]?.cancel()

                val timer = Timer()

                timer.schedule(object : TimerTask() {
                    override fun run() {

                        val oldText = previousFileContent[filePath] ?: ""

                        val oldKeys = extractKeys(oldText)
                        val newKeys = extractKeys(newText)

                        val removedKeys = oldKeys - newKeys
                        val addedKeys = newKeys - oldKeys

                        if (removedKeys.isNotEmpty() && addedKeys.isNotEmpty()) {

                            val oldKey = removedKeys.first()
                            val newKey = addedKeys.first()

                            println("🔥 Key changed: $oldKey → $newKey")

                            // Ensure indexing is complete
                            DumbService.getInstance(project).runWhenSmart {

                                val impactedFiles = ApplicationManager.getApplication().runReadAction<
                                        List<com.intellij.openapi.vfs.VirtualFile>
                                        > {
                                    findImpactedFiles(project, oldKey, filePath)
                                }

                                val message = buildString {
                                    append("Flutter Key Change Detected\n\n")
                                    append("Old Key: $oldKey\n")
                                    append("New Key: $newKey\n\n")

                                    append("Impacted Files:\n")
                                    if (impactedFiles.isEmpty()) {
                                        append("None found\n")
                                    } else {
                                        impactedFiles.forEach {
                                            append("• ${it.name}\n")
                                        }
                                    }

                                    append("\nApply changes across files?")
                                }

                                ApplicationManager.getApplication().invokeLater {
                                    val result = Messages.showYesNoDialog(
                                        project,
                                        message,
                                        "Flutter Key Impact Detector",
                                        "Yes",
                                        "No",
                                        null
                                    )

                                    if (result == Messages.YES) {
                                        replaceKeys(project, impactedFiles, oldKey, newKey)
                                        println("Changes applied")
                                    } else {
                                        println("User cancelled")
                                    }
                                }
                            }
                        }

                        // Update snapshot AFTER processing
                        previousFileContent[filePath] = newText
                    }
                }, debounceDelay)

                debounceTimers[filePath] = timer
            }

        }, project)
    }

    private fun extractKeys(text: String): Set<String> {
        return flutterKeyRegex.findAll(text)
            .map { it.groupValues[2] }
            .toSet()
    }

    private fun findImpactedFiles(
        project: Project,
        key: String,
        currentFilePath: String
    ) =
        FilenameIndex.getAllFilesByExt(project, "dart", GlobalSearchScope.projectScope(project))
            .filter { file ->

                if (file.path == currentFilePath) return@filter false

                val content = VfsUtil.loadText(file)

                val regex = Regex("""(Key|ValueKey)\s*\(\s*["']$key["']\s*\)""")

                regex.containsMatchIn(content)
            }

    private fun replaceKeys(
        project: Project,
        files: Collection<com.intellij.openapi.vfs.VirtualFile>,
        oldKey: String,
        newKey: String
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            files.forEach { file ->
                val content = VfsUtil.loadText(file)

                val updatedContent = content.replace(
                    """(Key|ValueKey)\s*\(\s*["']$oldKey["']\s*\)""".toRegex(),
                    """Key("$newKey")"""
                )

                VfsUtil.saveText(file, updatedContent)
            }
        }
    }
}