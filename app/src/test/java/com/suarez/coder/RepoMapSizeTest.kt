package com.suarez.coder

import com.suarez.coder.context.FileFilter
import com.suarez.coder.repomap.RepoMapBuilder
import com.suarez.coder.repomap.RepoSkeletonExtractor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Measures the map against this project's own sources. */
class RepoMapSizeTest {
    @Test fun mapOfThisProjectIsMuchSmallerThanItsSource() {
        val root = File("src/main").takeIf { it.exists() } ?: return
        val filter = FileFilter()
        val files = root.walkTopDown().filter { it.isFile && !filter.isExcluded(it.path, it.length()) }.toList()
        val raw = files.sumOf { it.readText().length }
        val map = RepoMapBuilder.build(files.associate { it.path to RepoSkeletonExtractor.extract(it.path, it.readText()) })
        println("REPOMAP files=${files.size} source=$raw chars map=${map.length} chars ratio=${"%.1f".format(map.length * 100.0 / raw)}%")
        assertTrue(map.length < raw * 0.3)
    }
}
