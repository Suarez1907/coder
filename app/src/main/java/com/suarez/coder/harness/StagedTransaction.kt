package com.suarez.coder.harness

/**
 * In-memory file tree for one run. DAG subtasks write here; a push happens only after the whole tree validates.
 * Nothing in this class talks to git.
 */
class StagedTransaction {
    data class Edit(val path: String, val before: String?, val after: String?)

    private val map = LinkedHashMap<String, Edit>()

    fun stage(path: String, before: String?, after: String?) {
        val key = path.trim().removePrefix("./")
        if (key.isNotBlank()) map[key] = Edit(key, before, after)
    }

    fun edit(path: String): Edit? = map[path.trim().removePrefix("./")]

    fun edits(): List<Edit> = map.values.toList()

    fun paths(): Set<String> = map.keys.toSet()

    fun clear() = map.clear()
}
