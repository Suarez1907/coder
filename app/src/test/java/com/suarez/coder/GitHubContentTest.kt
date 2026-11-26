package com.suarez.coder

import com.suarez.coder.tools.GitHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun releaseSummaryListsTagAssetsAndFlags() {
        val body = """[
            {"tag_name":"build-93","name":"build-93","draft":false,"prerelease":false,"published_at":"2025-01-02T03:04:05Z",
             "assets":[{"name":"Coder-93.apk","size":2097152,"browser_download_url":"https://x/Coder-93.apk"}]},
            {"tag_name":"v1.0.0-rc1","name":"Aday","draft":true,"prerelease":true,"published_at":"2024-12-01T00:00:00Z","assets":[]}
        ]"""
        val out = GitHub.releaseSummary(body, 5)
        assertTrue(out, out.contains("- build-93 2025-01-02"))
        assertTrue(out, out.contains("asset: Coder-93.apk (2.0 MB) https://x/Coder-93.apk"))
        assertTrue(out, out.contains("- v1.0.0-rc1 — Aday (taslak) (ön izleme) 2024-12-01"))
        assertFalse(out, out.contains("(taslak) (ön izleme) (taslak)"))
    }

    @Test fun releaseSummaryRespectsLimitAndEmptyList() {
        assertEquals("Bu repoda henüz sürüm (release) yok.", GitHub.releaseSummary("[]", 5))
        assertEquals("Sürüm listesi okunamadı.", GitHub.releaseSummary("{\"message\":\"Not Found\"}", 5))
        val body = """[{"tag_name":"a","assets":[]},{"tag_name":"b","assets":[]},{"tag_name":"c","assets":[]}]"""
        val out = GitHub.releaseSummary(body, 2)
        assertTrue(out, out.contains("- a"))
        assertTrue(out, out.contains("- b"))
        assertFalse(out, out.contains("- c"))
    }
}
