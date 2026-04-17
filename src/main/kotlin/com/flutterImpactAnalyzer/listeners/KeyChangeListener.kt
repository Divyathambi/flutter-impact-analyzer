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

    private val usageRegex = Regex("""Keys\.(\w+)""")

    private val previousFileContent = mutableMapOf<String, String>()
    private val debounceTimers = ConcurrentHashMap<String, Timer>()
    private val debounceDelay = 1200L

    override suspend fun execute(project: Project) {

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {

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

                        ApplicationManager.getApplication().invokeLater {

                            DumbService.getInstance(project).runWhenSmart {

                                // KEY RENAME FLOW
                                if (removedKeys.size == 1 && addedKeys.size == 1) {

                                    val oldKey = removedKeys.first()
                                    val newKey = addedKeys.first()

                                    println("Key changed: $oldKey → $newKey")

                                    val impactedFiles = ApplicationManager.getApplication().runReadAction<List<com.intellij.openapi.vfs.VirtualFile>> {
                                        findImpactedFiles(project, oldKey, filePath)
                                    }

                                    val message = buildString {
                                        append("Key Change Detected\n\n")
                                        append("Old key : $oldKey\n\n")
                                        append("New key : $newKey\n\n")

                                        append("Impacted Files:\n")
                                        if (impactedFiles.isEmpty()) {
                                            append("None\n")
                                        } else {
                                            impactedFiles.forEach { append("• ${it.name}\n") }
                                        }

                                        append("\nApply changes?")
                                    }

                                    val result = Messages.showYesNoDialog(
                                        project,
                                        message,
                                        "Key Rename Detector",
                                        "Yes",
                                        "No",
                                        null
                                    )

                                    if (result == Messages.YES) {
                                        replaceKeys(project, impactedFiles, oldKey, newKey, 0)
                                    }

                                    previousFileContent[filePath] = newText
                                    return@runWhenSmart
                                }


                                // WIDGET DEPTH FLOW
                                val key = newKeys.firstOrNull() ?: return@runWhenSmart

                                val oldDepth = compareWidgetCount(oldText, key)
                                val newDepth = compareWidgetCount(newText, key)

                                val depthChange = newDepth - oldDepth

                                if (depthChange != 0 && removedKeys.isEmpty() && addedKeys.isEmpty()) {

                                    println("Widget hierarchy changed")

                                    val impactedFiles = ApplicationManager.getApplication().runReadAction<List<com.intellij.openapi.vfs.VirtualFile>> {
                                        findImpactedFiles(project, key, filePath)
                                    }

                                    val message = buildString {
                                        append("Widget Hierarchy Change Detected\n\n")

                                        if (depthChange > 0) {
                                            append("Widget wrapped by $depthChange level(s)")
                                        } else {
                                            append("Widget unwrapped by ${-depthChange} level(s)")
                                        }

                                        append("\n\nUpdate test expectations?")
                                    }

                                    val result = Messages.showYesNoDialog(
                                        project,
                                        message,
                                        "Widget Depth Detector",
                                        "Yes",
                                        "No",
                                        null
                                    )

                                    if (result == Messages.YES) {
                                        replaceKeys(project, impactedFiles, key, key, depthChange)
                                    }
                                }

                                previousFileContent[filePath] = newText
                            }
                        }
                    }
                }, debounceDelay)

                debounceTimers[filePath] = timer
            }

        }, project)
    }

    // =========================
    // 📦 WIDGET COUNT UPDATE
    // =========================
    private fun updateWidgetCountInTests(
        content: String,
        key: String,
        depthChange: Int
    ): String {

        val keyPattern = """find\.byKey\s*\(\s*Key\s*\(\s*["']$key["']\s*\)\s*\)"""

        return content.replace(
            Regex("""expect\s*\(\s*$keyPattern\s*,\s*(.*?)\)""")
        ) { matchResult ->

            val fullMatch = matchResult.value

            val updatedMatcher = when {
                depthChange > 0 -> "findsWidgets"
                depthChange < 0 -> "findsOneWidget"
                else -> return@replace fullMatch
            }

            fullMatch.replace(Regex("""finds\w+"""), updatedMatcher)
        }
    }

    private fun compareWidgetCount(text: String, key: String): Int {
        val index = text.indexOf(key)
        if (index == -1) return 0

        val sub = text.substring(0, index)
        val open = sub.count { it == '(' }
        val close = sub.count { it == ')' }

        return open - close
    }

    private fun extractKeys(text: String): Set<String> {
        return usageRegex.findAll(text)
            .map { it.groupValues[1] }
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

                Regex("""\bKeys\.$key\b""").containsMatchIn(content) ||
                        Regex("""(Key|ValueKey)\s*\(\s*["']$key["']\s*\)""").containsMatchIn(content)
            }

    private fun replaceKeys(
        project: Project,
        files: Collection<com.intellij.openapi.vfs.VirtualFile>,
        oldKey: String,
        newKey: String,
        depthChange: Int
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            files.forEach { file ->

                var content = VfsUtil.loadText(file)

                // Replace Keys.xxx
                content = content.replace(
                    """\bKeys\.$oldKey\b""".toRegex(),
                    """Keys.$newKey"""
                )

                // Replace Key("xxx") (tests)
                content = content.replace(
                    """(Key|ValueKey)\s*\(\s*["']$oldKey["']\s*\)""".toRegex(),
                    """Key("$newKey")"""
                )

                // Update widget count
                if (file.path.contains("test")) {
                    content = updateWidgetCountInTests(content, newKey, depthChange)
                }

                VfsUtil.saveText(file, content)
            }
        }
    }
}