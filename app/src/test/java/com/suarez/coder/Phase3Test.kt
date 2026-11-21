package com.suarez.coder

import com.suarez.coder.cost.Ledger
import com.suarez.coder.cost.LedgerEntry
import com.suarez.coder.data.ProfileJson
import com.suarez.coder.data.ProjectProfile
import com.suarez.coder.data.SessionStore
import com.suarez.coder.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar

class ProfileJsonTest {
    @Test fun roundTripKeepsEveryField() {
        val p = ProjectProfile(repo = "me/app", branch = "dev", instructions = "Türkçe yaz\nKısa tut",
            architectModel = "gemini/gemini-4-hydrogen", editorModel = "deepseek/deepseek-chat",
            contextRef = "main", contextFiles = listOf("README.md", "app/build.gradle.kts"))
        val back = ProfileJson.fromJson(ProfileJson.toJson(listOf(p, ProjectProfile(repo = "me/other"))))
        assertEquals(2, back.size)
        assertEquals(p, back[0])
        assertEquals("app", back[0].name)
        assertEquals("", back[1].architectModel)
    }

    @Test fun brokenJsonGivesEmptyList() {
        assertTrue(ProfileJson.fromJson("").isEmpty())
        assertTrue(ProfileJson.fromJson("{nope").isEmpty())
    }

    @Test fun nameFallsBackWithoutRepo() {
        assertTrue(ProjectProfile(id = "abcd1234").name.startsWith("Profil abcd"))
    }
}

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun chat(text: String, vararg images: String) = listOf(
        ChatMessage.User(text, images.toList()), ChatMessage.Assistant("tamam: $text"),
    )

    @Test fun savesListsAndLoadsNewestFirst() {
        val store = SessionStore(tmp.newFolder("chats"))
        store.save("a", chat("İlk görev\nayrıntı"), "me/app", 0.01)
        Thread.sleep(5)
        store.save("b", chat("İkinci görev"), "me/web", 0.02)
        val list = store.list()
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals("İlk görev", list[1].title)
        assertEquals("me/app", list[1].repo)
        assertEquals(2, store.load("a").size)
    }

    @Test fun emptyChatIsNotListed() {
        val store = SessionStore(tmp.newFolder("chats"))
        store.save("x", listOf(ChatMessage.Notice("merhaba")), "", 0.0)
        assertTrue(store.list().isEmpty())
    }

    @Test fun resavingKeepsCreatedAtAndUpdatesCost() {
        val store = SessionStore(tmp.newFolder("chats"))
        store.save("a", chat("görev"), "me/app", 0.01)
        val first = store.list().single()
        Thread.sleep(5)
        store.save("a", chat("görev") + ChatMessage.Assistant("devam"), "me/other", 0.05)
        val second = store.list().single()
        assertEquals(first.createdAt, second.createdAt)
        assertEquals("me/app", second.repo) // first repo sticks
        assertEquals(0.05, second.costUsd, 1e-9)
    }

    @Test fun searchMatchesTitleRepoAndContent() {
        val store = SessionStore(tmp.newFolder("chats"))
        store.save("a", chat("Giriş ekranını düzelt"), "me/app", 0.0)
        store.save("b", chat("README güncelle") + ChatMessage.Assistant("NullPointerException bulundu"), "me/web", 0.0)
        assertEquals(listOf("a"), store.search("giriş").map { it.id })
        assertEquals(listOf("b"), store.search("ME/WEB").map { it.id })
        assertEquals(listOf("b"), store.search("nullpointer").map { it.id })
        assertTrue(store.search("yok-böyle-bir-şey").isEmpty())
    }

    @Test fun deleteRemovesSessionAndItsImages() {
        val dir = tmp.newFolder("chats")
        val img = File(tmp.newFolder("img"), "shot.jpg").apply { writeText("x") }
        val store = SessionStore(dir)
        store.save("a", chat("ekran", img.path), "", 0.0)
        store.save("b", chat("diğer"), "", 0.0)
        store.delete("a")
        assertEquals(listOf("b"), store.list().map { it.id })
        assertFalse(img.exists())
        assertFalse(File(dir, "a.json").exists())
        assertTrue(store.load("a").isEmpty())
    }

    @Test fun legacyChatIsMigratedOnce() {
        val root = tmp.newFolder("files")
        val legacy = File(root, "chat.json")
        legacy.writeText(com.suarez.coder.llm.MessageJson.toJson(chat("eski sohbet")))
        val store = SessionStore(File(root, "chats"), legacy)
        assertEquals("eski sohbet", store.list().single().title)
        assertFalse(legacy.exists())
        assertTrue(File(root, "chat.json.migrated").exists())
        assertEquals(1, SessionStore(File(root, "chats"), legacy).list().size)
    }
}

class LedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun at(year: Int, month: Int, day: Int) = Calendar.getInstance().apply { clear(); set(year, month - 1, day, 12, 0) }.timeInMillis

    private fun e(at: Long, repo: String, role: String, usd: Double, input: Long = 1000, cached: Long = 0) =
        LedgerEntry(at, "s", repo, role, "gemini-x", input, cached, 100, usd)

    @Test fun appendAndReadBack() {
        val ledger = Ledger(File(tmp.root, "ledger.jsonl"))
        val a = e(at(2026, 11, 1), "me/app", "architect", 0.01, cached = 500)
        ledger.append(listOf(a))
        ledger.append(listOf(a.copy(role = "editor")))
        assertEquals(listOf(a, a.copy(role = "editor")), ledger.readAll())
        assertTrue(Ledger.key("s", a.at, "architect", "gemini-x") in ledger.keys())
    }

    @Test fun roleGroups() {
        assertEquals("Mimar", Ledger.roleGroup("architect"))
        assertEquals("Uygulayıcı", Ledger.roleGroup("editor"))
        assertEquals("Uygulayıcı", Ledger.roleGroup("escalation"))
        assertEquals("Yönlendirici", Ledger.roleGroup("router"))
        assertEquals("Yönlendirici", Ledger.roleGroup("decision"))
    }

    @Test fun aggregateTotalsMonthsReposRolesAndCache() {
        val now = at(2026, 11, 21)
        val entries = listOf(
            e(at(2026, 11, 2), "me/app", "architect", 0.30, input = 1000, cached = 800),
            e(at(2026, 11, 3), "me/app", "editor", 0.10, input = 1000, cached = 200),
            e(at(2026, 10, 9), "me/web", "editor", 0.20, input = 2000, cached = 0),
            e(at(2026, 11, 4), "me/web", "decision", 0.05, input = 9999, cached = 0),
            e(at(2026, 11, 4), "", "cache", 0.01, input = 0),
        )
        val d = Ledger.aggregate(entries, now)
        assertEquals(0.66, d.totalUsd, 1e-9)
        assertEquals(0.46, d.monthUsd, 1e-9)
        assertEquals("me/app", d.byRepo.first().first)
        assertEquals(0.5, d.byRepo.first().third!!, 1e-9) // (800+200)/2000
        assertEquals(0.0, d.byRepo.first { it.first == "me/web" }.third!!, 1e-9) // decision excluded
        assertNull(d.byRepo.first { it.first == "(repo yok)" }.third)
        assertEquals(0.30, d.byRole.first { it.first == "Uygulayıcı" }.second, 1e-9)
        assertEquals(0.30, d.byRole.first { it.first == "Mimar" }.second, 1e-9)
        assertEquals(listOf(Ledger.monthKey(now), Ledger.monthKey(at(2026, 10, 9))), d.byMonth.map { it.first })
        assertEquals(1000.0 / 4000, d.cacheHitRate!!, 1e-9)
    }
}
