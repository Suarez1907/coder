package com.suarez.coder

import com.suarez.coder.evolution.MultiRepoMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRepoMeshTest {

    @Test
    fun filtersByStackTagLimitsToFiveRulesAndCapsAt2000Chars() {
        val mesh = MultiRepoMesh()
        // Add 7 long Kotlin rules (only top 5 should be selected)
        for (i in 1..7) {
            mesh.record(
                stackTag = "kotlin",
                text = "Kotlin kural #$i: " + "detay ".repeat(90),
                confidence = 0.72 + i * 0.03,
                sourceRepo = "Suarez1907/coder",
            )
        }
        // Add unrelated GDScript & webOS rules (must NOT leak into Kotlin repo context)
        mesh.record("gdscript", "Godot sahnesinde Node3D eksenini kontrol et", 0.95, "Suarez1907/dino")
        mesh.record("webos", "webOS appinfo.json ikon boyutunu doğrula", 0.95, "Suarez1907/tv")
        // Add low-confidence Kotlin rule (below 0.70 threshold)
        mesh.record("kotlin", "Düşük güvenli kural", 0.40, "Suarez1907/coder")

        val tags = MultiRepoMesh.detectStackTags(listOf("app/src/main/java/Main.kt", "app/build.gradle.kts"))
        assertTrue("kotlin" in tags)
        assertTrue("gradle" in tags)
        assertFalse("gdscript" in tags)

        val selected = mesh.selectForTags(tags)
        assertEquals(5, selected.size)
        assertTrue(selected.all { it.stackTag == "kotlin" })
        assertTrue(selected.none { it.text.contains("Düşük güvenli") })

        val formatted = mesh.formatContext(tags)
        assertNotNull(formatted)
        assertTrue("Context must not exceed 2000 chars, got ${formatted!!.length}", formatted.length <= MultiRepoMesh.MAX_CONTEXT_CHARS)
        assertFalse(formatted.contains("Node3D"))
        assertFalse(formatted.contains("appinfo.json"))
    }
}
