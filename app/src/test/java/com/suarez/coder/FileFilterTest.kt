package com.suarez.coder

import com.suarez.coder.context.FileFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFilterTest {
    private val f = FileFilter("# comment\n*.log\n/secret/\ngenerated/\n!keep.log\ndocs/**/*.tmp\n")

    @Test fun defaultExclusions() {
        assertTrue(f.isExcluded("node_modules/react/index.js"))
        assertTrue(f.isExcluded("app/build/outputs/a.txt"))
        assertTrue(f.isExcluded(".git/config"))
        assertTrue(f.isExcluded("web/dist/main.js"))
        assertTrue(f.isExcluded("package-lock.json"))
        assertTrue(f.isExcluded("sub/yarn.lock"))
        assertTrue(f.isExcluded("res/icon.png"))
        assertTrue(f.isExcluded("lib/foo.jar"))
        assertTrue(f.isExcluded("big.txt", 500_000))
        assertFalse(f.isExcluded("src/main/App.kt", 1000))
        assertFalse(f.isExcluded("build.gradle.kts"))
    }

    @Test fun gitignoreRules() {
        assertTrue(f.isExcluded("logs/app.log"))
        assertFalse(f.isExcluded("keep.log"))
        assertTrue(f.isExcluded("secret/key.txt"))
        assertFalse(f.isExcluded("src/secret/key.txt")) // anchored
        assertTrue(f.isExcluded("src/generated/A.kt")) // dir rule anywhere
        assertFalse(f.isExcluded("src/generated")) // dir-only rule does not match a file
        assertTrue(f.isExcluded("docs/a/b/x.tmp"))
    }
}
