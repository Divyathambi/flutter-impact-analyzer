package com.flutterImpactAnalyzer.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService

import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import java.util.TimerTask

class KeyChangeListener : ProjectActivity {

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

                        ApplicationManager.getApplication().invokeLater {

                            DumbService.getInstance(project).runWhenSmart {

                                // Key rename for variable inside keys.dart
                                val variableChange = findChangedKeyInDefinition(oldText, newText)

                                // Key rename detection for multi key usages
                                val usageChange = findChangedKey(oldText, newText)

                                val change = when {
                                    variableChange != null -> variableChange
                                    usageChange != null -> usageChange
                                    else -> null
                                }

                                if (change != null) {

                                    val (oldKey, newKey) = change
                                    println("Key changed: $oldKey → $newKey")

                                    val impactedFiles = ApplicationManager.getApplication()
                                        .runReadAction<List<com.intellij.openapi.vfs.VirtualFile>> {
                                            findImpactedFilesPsi(project, oldKey, filePath)
                                        }

                                    val message = buildString {
                                        append("Key Change Detected\n\n")
                                        append("Old key: $oldKey\n")
                                        append("New key: $newKey\n\n")


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
                                        "Flutter Key Change  Detector",
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


                                // Function to detect widget tree changes
                                val keys = extractKeys(newText)
                                val key = keys.firstOrNull() ?: return@runWhenSmart

                                val oldDepth = compareWidgetCount(oldText, key)
                                val newDepth = compareWidgetCount(newText, key)

                                val depthChange = newDepth - oldDepth

                                if (depthChange != 0) {

                                    println("Widget hierarchy changed")

                                    val impactedFiles = ApplicationManager.getApplication()
                                        .runReadAction<List<com.intellij.openapi.vfs.VirtualFile>> {
                                            findImpactedFilesPsi(project, key, filePath)
                                        }

                                    val message = buildString {
                                        append("Widget Hierarchy Change Detected\n\n")

                                            impactedFiles.forEach {

                                                if(it.path.contains("test")) {
                                                    append("Impacted Test Files: \n")
                                                }

                                                append("• ${it.name}\n")
                                        }

                                        if (depthChange > 0) {
                                            append("Widget wrapped by $depthChange level(s)")
                                        } else {
                                            append("Widget unwrapped by ${-depthChange} level(s)")
                                        }

                                        impactedFiles.forEach {
                                            if(it.path.contains("test")) {
                                                append("Impacted Test Files: \n")

                                            }
                                        }
                                    }

                                    Messages.showYesNoDialog(
                                        project,
                                        message,
                                        "Flutter Widget Tree Change Detector",
                                        null
                                    )
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

    // Multi key change detection
    private fun findChangedKey(oldText: String, newText: String): Pair<String, String>? {

        val oldMatches = Regex("""Keys\.(\w+)""")
            .findAll(oldText)
            .map { it.groupValues[1] }
            .toList()

        val newMatches = Regex("""Keys\.(\w+)""")
            .findAll(newText)
            .map { it.groupValues[1] }
            .toList()

        if (oldMatches.size != newMatches.size) return null

        for (i in oldMatches.indices) {
            if (oldMatches[i] != newMatches[i]) {
                return oldMatches[i] to newMatches[i]
            }
        }

        return null
    }


    // Function for PSI based search
    private fun findImpactedFilesPsi(
        project: Project,
        key: String,
        currentFilePath: String
    ): List<com.intellij.openapi.vfs.VirtualFile> {

        val result = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()

        val scope = GlobalSearchScope.projectScope(project)

        val keyUsageRegex = Regex("""\bKeys\.$key\b""")
        val keyCtorRegex = Regex("""(Key|ValueKey)\s*\(\s*["']$key["']\s*\)""")

        val files = FilenameIndex.getAllFilesByExt(
            project,
            "dart",
            scope
        )

        for (file in files) {

            if (file.path == currentFilePath) continue

            val content = try {
                VfsUtilCore.loadText(file)
            } catch (e : Exception) {
                println("Failed to load file: $e")
                continue
            }

            if(!content.contains(key)) continue

            if(keyUsageRegex.containsMatchIn(content) || keyCtorRegex.containsMatchIn(content)) {
                result.add(file)
            }
        }

        return result
    }


    // Widget count comparison
    private fun compareWidgetCount(text: String, key: String): Int {

        val matches = Regex("""Keys\.$key""").findAll(text).toList()
        if (matches.isEmpty()) return 0

        val index = matches.first().range.first

        val sub = text.substring(0, index)

        val open = sub.count { it == '(' }
        val close = sub.count { it == ')' }

        return open - close
    }

    private fun extractKeys(text: String): Set<String> {
        return Regex("""Keys\.(\w+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
    }


    // Apply for only widget count updation
    private fun applyWidgetCountUpdate(
        project: Project,
        files: Collection<com.intellij.openapi.vfs.VirtualFile>,
        key: String,
        depthChange: Int
    ) {
        WriteCommandAction.runWriteCommandAction(project) {

            files.forEach { file ->

                if (!file.path.contains("test")) return@forEach

                var content = VfsUtil.loadText(file)

                content = updateWidgetCountInTests(content, key, depthChange)

                VfsUtil.saveText(file, content)
            }
        }
    }


    // Update flutter tests based on widget tree change
    private fun updateWidgetCountInTests(
        content: String,
        key: String,
        depthChange: Int
    ): String {

        val keyPattern =
            """find\.byKey\s*\(\s*(Keys\.$key|Key\s*\(\s*["']$key["']\s*\))\s*\)"""

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


    // Replace Keys
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

                // Replace Key("xxx")
                content = content.replace(
                    """(Key|ValueKey)\s*\(\s*["']$oldKey["']\s*\)""".toRegex(),
                    """Key("$newKey")"""
                )

                // Apply widget count if test file
                if (file.path.contains("test")) {
                    content = updateWidgetCountInTests(content, newKey, depthChange)
                }

                VfsUtil.saveText(file, content)
            }
        }
    }
}

private fun findChangedKeyInDefinition(
    oldText: String,
    newText: String,
) : Pair<String, String>? {
    val regex = Regex("""static\s+const\s+(\w+)\s*=\s*Key""")

    val oldKeys = regex.findAll(oldText).map { it.groupValues[1] }.toList()
    val newKeys = regex.findAll(newText).map { it.groupValues[1] }.toList()

    if(oldKeys.size != newKeys.size) return null

    for(i in oldKeys.indices) {
        if(oldKeys[i] != newKeys[i]) {
            return oldKeys[i] to newKeys[i]
        }
    }

    return null
}

/**
 * What is our motive?
 * 1) Pop up should detect the changes in the key - This is done
 * 2) Pop up should detect the widget tree changes - This is also done
 *
 * What is the problem facing?
 * 1) The update widget count is only working if the current working file is a test file
 *
 * What it should do?
 * 1) It should ideally find the test files, make the changes there.
 *
 * What can I do to achieve that?
 *
 * 1) Find all the files using PSI manager. List<com.intellij.openapi.vfs.VirtualFile>
 * 2) Check whether there are any test files. Check whether any of the file in the list, the path of it, contains the name "test"
 * 3) IF there are any test files with the key and IF there is a widget tree change -> Then show the pop up with the list of test files impacted.
 */