package com.suarez.coder

import com.suarez.coder.apk.ApkDownloader
import com.suarez.coder.apk.ApkLinks
import com.suarez.coder.apk.ApkTarget
import com.suarez.coder.context.HistoryPruner
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.GeminiClient
import com.suarez.coder.llm.MessageJson
import com.suarez.coder.media.ImagePrep
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkLinksTest {
    @Test fun findsReleaseFilesTagsArtifactsAndDirectLinks() {
        val text = """
            APK: https://github.com/Suarez1907/coder/releases/download/build-18/Coder-18.apk
            Sürüm: https://github.com/Suarez1907/coder/releases/tag/build-17.
            Artifact: https://github.com/o/r/actions/runs/123/artifacts/456
            Başka: https://example.com/files/app-release.apk
        """.trimIndent()
        val found = ApkLinks.find(text, max = 5).map { it.second }
        assertEquals(ApkTarget.ReleaseFile("Suarez1907", "coder", "build-18", "Coder-18.apk"), found[0])
        assertTrue(found.contains(ApkTarget.Artifact("o", "r", 456)))
        assertTrue(found.contains(ApkTarget.ReleaseTag("Suarez1907", "coder", "build-17")))
        assertTrue(found.contains(ApkTarget.Direct("https://example.com/files/app-release.apk")))
        assertTrue(ApkLinks.find("hiç bağlantı yok").isEmpty())
    }
}

class ApkDownloaderTest {
    private fun dir() = Files.createTempDirectory("apk").toFile()

    @Test fun authorizationNeverReachesTheRedirectTarget() = runBlocking {
        val api = MockWebServer(); val storage = MockWebServer()
        api.start(); storage.start()
        storage.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048) { 7 })))
        api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/blob/app.apk").toString()))
        val dl = ApkDownloader({ "secret-token" }, dir(), OkHttpClient(), apiBase = api.url("").toString().trimEnd('/'))
        val dest = File(dir(), "x")
        dl.fetch(api.url("/repos/o/r/releases/assets/1").toString(), "application/octet-stream", dest) {}
        val toApi = api.takeRequest()
        val toStorage = storage.takeRequest()
        assertEquals("Bearer secret-token", toApi.getHeader("Authorization"))
        assertEquals("application/octet-stream", toApi.getHeader("Accept"))
        assertNull("token leaked to storage host", toStorage.getHeader("Authorization"))
        assertEquals(2048L, dest.length())
        api.shutdown(); storage.shutdown()
    }

    @Test fun thirdPartyLinksGetNoToken() = runBlocking {
        val api = MockWebServer(); val other = MockWebServer()
        api.start(); other.start()
        other.enqueue(MockResponse().setBody("apk"))
        val dl = ApkDownloader({ "secret-token" }, dir(), OkHttpClient(), apiBase = api.url("").toString().trimEnd('/'))
        dl.fetch(other.url("/app.apk").toString(), "*/*", File(dir(), "y")) {}
        assertNull(other.takeRequest().getHeader("Authorization"))
        api.shutdown(); other.shutdown()
    }

    @Test fun artifactZipIsUnpacked() = runBlocking {
        val api = MockWebServer(); val storage = MockWebServer()
        api.start(); storage.start()
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("app-release.apk")); z.write("APK-BYTES".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/artifact.zip").toString()))
        storage.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val out = dir()
        val dl = ApkDownloader({ "t" }, out, OkHttpClient(), apiBase = api.url("").toString().trimEnd('/'))
        var last = -1
        val file = dl.download(ApkTarget.Artifact("o", "r", 9)) { last = it }
        assertEquals("APK-BYTES", file.readText())
        assertTrue(file.name.endsWith(".apk"))
        assertEquals(100, last)
        assertTrue(api.takeRequest().path!!.endsWith("/repos/o/r/actions/artifacts/9/zip"))
        api.shutdown(); storage.shutdown()
    }

    @Test fun artifactsNeedAToken() {
        val dl = ApkDownloader({ "" }, dir(), OkHttpClient())
        val e = runCatching { dl.resolve(ApkTarget.Artifact("o", "r", 1)) }.exceptionOrNull()
        assertTrue(e is ApkDownloader.DownloadException)
    }
}

class VisionTest {
    @Test fun resizeMath() {
        assertEquals(1568 to 784, ImagePrep.targetSize(3136, 1568))
        assertEquals(706 to 1568, ImagePrep.targetSize(1080, 2400)) // 705.6 rounds up
        assertEquals(800 to 600, ImagePrep.targetSize(800, 600)) // never upscales
        assertEquals(2, ImagePrep.sampleSize(4000, 3000))
        assertEquals(1, ImagePrep.sampleSize(1080, 2400))
    }

    @Test fun imagesGoToGeminiAsInlineDataOnlyInTheirOwnMessage() {
        val img = File.createTempFile("shot", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val history = listOf(
            ChatMessage.User("eski mesaj"),
            ChatMessage.Assistant("tamam"),
            ChatMessage.User("bu hata ne?", listOf(img.absolutePath)),
        )
        val contents = GeminiClient("k", "gemini-3.8-flash").contentsForTest(history)
        assertEquals(1, contents.getJSONObject(0).getJSONArray("parts").length())
        val parts = contents.getJSONObject(2).getJSONArray("parts")
        assertEquals("bu hata ne?", parts.getJSONObject(0).getString("text"))
        val inline = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("image/jpeg", inline.getString("mimeType"))
        assertEquals("AQID", inline.getString("data"))
    }

    @Test fun imagesSurviveSaveAndContextMergeButNotCompaction() {
        val u = ChatMessage.User("bak", listOf("/x/a.jpg", "/x/b.jpg"))
        assertEquals(u, MessageJson.fromJson(MessageJson.toJson(listOf(u))).single())
        val merged = HistoryPruner.prepare(listOf(u, ChatMessage.ContextBlock("dosya içeriği", "a.kt"))).messages.single() as ChatMessage.User
        assertEquals(2, merged.images.size)
        val old = HistoryPruner.compactOld(u) as ChatMessage.User
        assertTrue(old.images.isEmpty())
        assertTrue(old.text.contains("2 ekran görüntüsü"))
        assertFalse(HistoryPruner.compactOld(ChatMessage.User("x")).let { (it as ChatMessage.User).text.contains("ekran") })
    }
}
