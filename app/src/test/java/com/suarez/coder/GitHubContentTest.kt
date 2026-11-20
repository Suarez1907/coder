package com.suarez.coder

import com.suarez.coder.tools.GitHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubContentTest {
    @Test fun tomlStartingWithBracketIsAFileNotAFolder() {
        // The case from the phone: gradle/libs.versions.toml begins with "[versions]".
        assertNull(GitHub.directoryListing("[versions]\nagp = \"8.7.3\"\nkotlin = \"2.0.21\"\n"))
        assertNull(GitHub.directoryListing("[1, 2, 3]"))
        assertNull(GitHub.directoryListing("[]"))
    }

    @Test fun realFolderListing() {
        val body = """[{"type":"file","path":"app/A.kt","name":"A.kt"},{"type":"dir","path":"app/ui","name":"ui"}]"""
        assertEquals("(Bu bir klasör)\nfile: app/A.kt\ndir: app/ui", GitHub.directoryListing(body))
    }
}
