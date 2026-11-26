package com.suarez.coder

import com.suarez.coder.ci.CiKnowledgeBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CiKnowledgeBaseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun kb() = CiKnowledgeBase(tmp.newFile("ci-lessons.json").also { it.delete() })

    @Test fun repeatedErrorUpdatesOneLesson() {
        val k = kb()
        k.record("me/app", "e: file:///w/app/Main.kt:12:5 Unresolved reference 'foo'.", "import eklendi", 1)
        k.record("me/app", "e: file:///w/app/Main.kt:40:9 Unresolved reference 'foo'.", "import ve tip düzeltildi", 2)
        assertEquals(1, k.all().size)
        val l = k.all().single()
        assertEquals(2, l.hits)
        assertEquals(CiKnowledgeBase.Cause.COMPILE, l.cause)
        assertTrue(l.fix.contains("tip düzeltildi"))
    }

    @Test fun lookupPrefersSameRepoAndHints() {
        val k = kb()
        k.record("me/other", "Module not found: Can't resolve 'lodash'", "npm i lodash", 1)
        k.record("me/app", "Module not found: Can't resolve 'lodash'", "paket eklendi", 2)
        val found = k.lookup("me/app", "Module not found: Can't resolve 'lodash'")
        assertEquals("me/app", found.first().repo)
        val hint = k.hint(found)!!
        assertTrue(hint.startsWith("CI KNOWLEDGE"))
        assertEquals(CiKnowledgeBase.Cause.DEPENDENCY, found.first().cause)
        assertNull(k.hint(k.lookup("me/app", "tamamen başka bir hata")))
    }

    @Test fun causesAndForget() {
        assertEquals(CiKnowledgeBase.Cause.INFRA, CiKnowledgeBase.Cause.of("Could not resolve host: api.github.com"))
        assertEquals(CiKnowledgeBase.Cause.COMPILE, CiKnowledgeBase.Cause.of("Type mismatch: expected Int"))
        assertEquals(CiKnowledgeBase.Cause.TEST, CiKnowledgeBase.Cause.of("FooTest > bar FAILED"))
        val k = kb()
        k.record("me/app", "FooTest > bar FAILED", "test düzeltildi", 1)
        assertTrue(k.markdown("me/app").contains("test"))
        assertTrue(k.forget("me/app", "FooTest > bar FAILED"))
        assertFalse(k.forget("me/app", "FooTest > bar FAILED"))
        assertTrue(k.all().isEmpty())
    }
}
