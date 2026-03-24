package com.flutterImpactAnalyzer.listeners

import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

object KeyUsageFinder {

    fun findFilesUsingKey(
        project: Project,
        key: String,
    ) : List<String> {
        val results = mutableListOf<String>()

        val files = FilenameIndex.getAllFilesByExt(
            project,
            "dart",
            GlobalSearchScope.projectScope(project)
        )

        for(file in files) {
            val text = String(file.contentsToByteArray())

            if(text.contains(key)) {
                results.add(file.path)
            }
        }

        return results
    }
}