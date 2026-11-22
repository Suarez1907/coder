package com.suarez.coder.ci

import org.junit.Test
import org.junit.Assert.*

class LogFilterTest {

    @Test
    fun legalBoilerplate_doesNotTriggerError() {
        val license = """
            /*
             * Copyright (c) 2023 Example Corp.
             *
             * THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
             * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
             * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
             * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR
             * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
             * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
             * CONNECTION WITH THE SOFTWARE. FAILURE OF ESSENTIAL PURPOSE
             * IS NOT WARRANTED. EXCEPTION TO LIABILITY LIMITATIONS APPLY.
             */
        """.trimIndent()

        val result = LogFilter.extract(license)

        assertTrue("Should contain fallback header", result.contains("(Belirgin hata satırı bulunamadı; logun son 40 satırı)"))
    }

    @Test
    fun realGradleFailure_triggersError() {
        val log = """
            2024-01-01T00:00:00Z > Task :compileKotlin FAILED
            2024-01-01T00:00:01Z e: /path/File.kt: Unresolved reference: foo
            2024-01-01T00:00:02Z FAILURE: Build failed with an exception.
        """.trimIndent()

        val result = LogFilter.extract(log)

        assertTrue("Should contain FAILED", result.contains("FAILED"))
        assertTrue("Should contain Unresolved reference", result.contains("Unresolved reference"))
        assertFalse("Should not contain fallback header", result.contains("(Belirgin hata satırı bulunamadı; logun son 40 satırı)"))
    }
}
