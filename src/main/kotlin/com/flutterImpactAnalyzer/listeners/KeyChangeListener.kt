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

                        val isTestFile = file.path.contains("test") || file.name.contains("test")

                        if (removedKeys.isNotEmpty() && addedKeys.isNotEmpty()) {

                            val oldKey = removedKeys.first()
                            val newKey = addedKeys.first()

                            // Widget count detection
                            val oldDepth = compareWidgetCount(oldText, oldKey)
                            val newDepth = compareWidgetCount(newText, newKey)

                            val depthChange = newDepth - oldDepth

                            print("Depth change: $depthChange")



                            println("🔥 Key changed: $oldKey → $newKey")

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

                                    if(depthChange != 0) {
                                        append("Widget hierarchy change detected\n\n")

                                        if(depthChange > 0) {
                                            append("Widget has been wrapped with $depthChange widget(s)")
                                        } else {
                                            append("Widget has been unwrapped with ${-depthChange} widget(s)")
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
                                        replaceKeys(project, impactedFiles, oldKey, newKey, depthChange)
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

    // Function to update widget count in tests
    /**
     *
     * Objective: Create a function that can update the widget count in tests when the widget tree surrounding a key
     * changes
     *
     * Implementation:
     *
     * 1) There are 3 ways to find the widget count in flutter test
     *
     * a) findsOneWidget - If the widget count is 1
     * b) findsWidgets - If there are multiple widgets
     * c) findNothing - If there are no widgets
     *
     * 2) Find where the widget count instance is being used - use regex(),
     * 3) If there are matching results, then proceed with the changes.
     * 5) Arguments - int depthChange, String content, String key
     * 6) Return type of this function - String
     **/

    private fun updateWidgetCountInTests(
        content : String,
        key: String,
        depthChange : Int,
    ) : String {
        val keyPattern = """find\.byKey\s*\(\s*Key\s*\(\s*["']$key["']\s*\)\s*\)"""

        // replace the content with the changes accordingly

        return content.replace(
            Regex("""expect\s*\(\s*$keyPattern\s*,\s*(.*?)\)"""),
        ) {
            matchResult ->

            val fullMatch = matchResult.value

            val updatedMatcher = when {
                depthChange > 0 -> "findsWidgets"
                depthChange < 0 -> "findsOneWidget"
                else -> return@replace fullMatch
            }

            fullMatch.replace(
                Regex("""finds\w+"""),
                updatedMatcher
            )
        }
    }

    // Function to detect changes in the widget tree and prompt the user to make changes in the widget count accordingly.
    /**
     * Objective : Create a function that can detect the changes in the flutter widget tree and prompt the users to make
     * changes in the widget tree accordingly.
     *
     * Implementation :
     *
     * 1) variable to store the count of old open and closed parenthesis
     * 2) Compare the count of old open and closed parenthesis
     * 3) If the new count has increased -> widget tree changes -> increase the count by 1
     * 4) If the new count has decreased -> widget tree changes -> decrease the count by 1
     * **/

    private fun compareWidgetCount(text: String, key: String): Int {
        val index = text.indexOf(key);
        if(index == -1) return 0

        val subString = text.substring(0, index)

        val openParenth = subString.count{it == '('}
        val closeParenth = subString.count{it == ')'}

        return openParenth - closeParenth;
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
        newKey: String,
        depthChange: Int
    ) {
        WriteCommandAction.runWriteCommandAction(project) {
            files.forEach { file ->
                val content = VfsUtil.loadText(file)

                var updatedContent = content.replace(
                    """(Key|ValueKey)\s*\(\s*["']$oldKey["']\s*\)""".toRegex(),
                          """Key("$newKey")"""
                )

                // Apply widget count change for test files
                if(file.path.contains("test")) {
                    updatedContent = updateWidgetCountInTests(
                        updatedContent,
                        newKey,
                        depthChange,
                    )
                }

                VfsUtil.saveText(file, updatedContent)
            }
        }
    }
}