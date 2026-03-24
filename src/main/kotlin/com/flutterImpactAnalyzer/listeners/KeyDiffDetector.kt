package org.example

data class KeyChange (
    val oldKey: String,
    val newKey: String
)

object KeyDiffDetector {
    private val fileKeyMap = mutableMapOf<String, List<String>>()

    fun detectChange(
        filePath: String,
        newKeys: List<String>,
    ) : KeyChange? {

        val oldKeys = fileKeyMap[filePath]

        fileKeyMap[filePath] = newKeys

        if(oldKeys == null) return null

        if(oldKeys.size != newKeys.size) return null

        for(i in oldKeys.indices) {
            if(oldKeys[i] != newKeys[i]) {
                return KeyChange(
                    oldKey = oldKeys[i],
                    newKey = newKeys[i],
                )
            }
        }

        return null
    }
}