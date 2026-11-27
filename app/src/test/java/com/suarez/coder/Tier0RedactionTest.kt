package com.suarez.coder

import com.suarez.coder.edge.HeuristicFallbackBackend
import com.suarez.coder.edge.LiteRtLmBackend
import com.suarez.coder.edge.Tier0Class
import com.suarez.coder.edge.Tier0EdgeEngine
import com.suarez.coder.edge.Tier0Flags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Tier0RedactionTest {
    private val engine = Tier0EdgeEngine(HeuristicFallbackBackend(), Tier0Flags())

    @get:Rule val tmp = TemporaryFolder()

    @Test fun masksSecretsDeterministicallyAndRestoresLocally() {
        val text = "anahtar ghp_abcdefghijklmnopqrstuvwxyz0123456789 ve ali@gmail.com ip 10.1.2.3"
        val first = engine.redact(text)
        val second = engine.redact(text)
        assertEquals(first.masked, second.masked)
        assertFalse(first.masked.contains("ghp_"))
        assertFalse(first.masked.contains("ali@gmail.com"))
        assertFalse(first.masked.contains("10.1.2.3"))
        assertTrue(first.masked.contains("[REDACTED_1]"))
        assertTrue(first.masked.contains("[REDACTED_2]"))
        assertTrue(first.masked.contains("[REDACTED_3]"))
        val restored = engine.restore(first.masked, first.vault)
        assertTrue(restored.contains("ghp_abcdefghijklmnopqrstuvwxyz0123456789"))
        assertTrue(restored.contains("ali@gmail.com"))
        assertTrue(restored.contains("10.1.2.3"))
        assertTrue(engine.redact("not user@example.com").masked.contains("user@example.com"))
    }

    @Test fun missingModelFallsBackWithoutCrashing() = runBlocking {
        assertEquals("NONE", engine.accelerator)
        assertEquals("Model seçilmedi · HeuristicFallback", LiteRtLmBackend(null).status())
        val voice = "şurdaki butonu büyüt bir de maliyet ekranı kesilmesin"
        val shaped = engine.structureVoice(voice)
        assertEquals(engine.structureVoiceNow(voice), shaped)
        assertTrue(shaped.contains("1."))
        assertTrue(shaped.contains("2."))
        assertTrue(shaped.lowercase().contains("maliyet"))
        assertTrue(shaped.lowercase().contains("buton"))
        assertEquals(Tier0Class.SIMPLE_QUESTION, engine.classify("bu sınıf nedir?"))
        assertEquals(Tier0Class.FILE_INTENT, engine.classify("Settings.kt dosyasına bak"))
        assertEquals(Tier0Class.COMPLEX, engine.classify("ödeme ekranını ekle ve testleri düzelt"))
    }

    @Test fun litertOrderAndGgufDoNotCrashOrEmbedWeights() = runBlocking {
        assertEquals(listOf("NPU", "GPU", "CPU"), LiteRtLmBackend.ACCEL_ORDER)
        val gguf = tmp.newFile("tiny.gguf")
        val ggufBackend = LiteRtLmBackend(gguf.absolutePath)
        assertNull(ggufBackend.complete("merhaba"))
        assertTrue(ggufBackend.status().contains("GGUF"))
        val model = tmp.newFile("local.litertlm")
        val bridge = LiteRtLmBackend(model.absolutePath)
        assertNull(bridge.complete("merhaba"))
        assertFalse(bridge.available)
        assertTrue(bridge.status().contains("HeuristicFallback"))
    }
}
