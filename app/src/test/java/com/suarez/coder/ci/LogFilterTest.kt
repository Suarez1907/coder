package com.suarez.coder.ci

import org.junit.Test
import org.junit.Assert.*

class LogFilterTest {

    @Test
    fun legalBoilerplate_returnsTailNotError() {
        val license = """
            MIT License

            Copyright (c) 2023 Example Corp.

            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE. FAILURE EXCEPTION ERROR LIABILITY DAMAGES
        """.trimIndent()

        val result = LogFilter.extract(license)

        assertTrue("Should contain fallback header", result.contains("(Belirgin hata satırı bulunamadı; logun son 40 satırı)"))
    }

    @Test
    fun realBuildFailure_detected() {
        val log = """
            2024-01-01T00:00:00Z > Task :app:compileKotlin FAILED
            FAILURE: Build failed with an exception.
            Caused by: org.gradle.api.TaskExecutionException: Execution failed for task ':app:compileKotlin'.
            Some other line
        """.trimIndent()

        val result = LogFilter.extract(log)

        assertTrue("Should contain FAILURE:", result.contains("FAILURE:"))
        assertTrue("Should contain Caused by:", result.contains("Caused by:"))
        assertFalse("Should not contain fallback header", result.contains("(Belirgin hata satırı bulunamadı; logun son 40 satırı)"))
    }
}
