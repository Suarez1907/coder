package com.suarez.coder

import com.suarez.coder.ci.AutoShip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoShipTest {

    private fun release(
        tag: String,
        commitish: String = "main",
        apk: Boolean = true,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = """
        {"tag_name":"$tag","name":"Coder ${tag.removePrefix("build-")}","draft":$draft,"prerelease":$prerelease,
         "target_commitish":"$commitish","html_url":"https://github.com/o/r/releases/tag/$tag",
         "assets":[${if (apk) """{"name":"Coder-1.apk","size":10,"browser_download_url":"https://x/$tag.apk"}""" else ""}]}
    """.trimIndent()

    private fun list(vararg items: String) = "[" + items.joinToString(",") + "]"

    @Test
    fun picksReleaseOfTheSameCommit() {
        val body = list(
            release("build-102", commitish = "aaaabbbbccccddddeeeeffff0000111122223333"),
            release("build-101", commitish = "1111222233334444555566667777888899990000"),
        )
        val ship = AutoShip.pick(body, "aaaabbbbccccddddeeeeffff0000111122223333", "main")
        assertEquals("build-102", ship?.tag)
        assertEquals("https://x/build-102.apk", ship?.apkUrl)
    }

    @Test
    fun matchesShortShaAndBranchName() {
        val body = list(release("build-7", commitish = "fe1f0d0aa"))
        assertEquals("build-7", AutoShip.pick(body, "fe1f0d0", null)?.tag)
        assertEquals("build-7", AutoShip.pick(list(release("build-7", commitish = "ccr-branch")), null, "ccr-branch")?.tag)
    }

    @Test
    fun skipsDraftsAndFallsBackToNewestPublished() {
        val body = list(
            release("build-200", commitish = "dev", draft = true),
            release("build-199", commitish = "dev", prerelease = true),
            release("build-198", commitish = "dev"),
        )
        assertEquals("build-198", AutoShip.pick(body, "no-such-sha", null)?.tag)
    }

    @Test
    fun missingAssetIsNoApk() {
        val body = list(release("build-5", commitish = "abc1234", apk = false))
        val ship = AutoShip.pick(body, "abc1234")
        assertEquals(AutoShip.State.NO_APK, AutoShip.state(ship))
        assertNull(ship?.apkUrl)
        assertTrue(AutoShip.warning(ship, "o/r", "abc1234", null)!!.contains("APK eki yok"))
    }

    @Test
    fun unreadableBodyIsNoRelease() {
        assertNull(AutoShip.pick("{\"message\":\"Not Found\"}"))
        assertNull(AutoShip.pick("[]"))
        assertEquals(AutoShip.State.NO_RELEASE, AutoShip.state(null))
        val w = AutoShip.warning(null, "o/r", "abc1234ff", "https://github.com/o/r/actions/runs/9")
        assertTrue(w!!.contains("sürüm yayınlanmadı"))
        assertTrue(w.contains("runs/9"))
    }

    @Test
    fun shippedReleaseHasNoWarning() {
        val ship = AutoShip.pick(list(release("build-3", commitish = "deadbee")), "deadbee")
        assertEquals(AutoShip.State.SHIPPED, AutoShip.state(ship))
        assertNull(AutoShip.warning(ship, "o/r", "deadbee", null))
    }
}
