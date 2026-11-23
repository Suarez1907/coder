package com.suarez.coder

import com.suarez.coder.cloudtools.CloudWorkflowTemplates
import com.suarez.coder.orchestrator.Evaluator
import com.suarez.coder.orchestrator.OrchestratorMode
import com.suarez.coder.orchestrator.OrchestratorPlan
import com.suarez.coder.orchestrator.SubTask
import com.suarez.coder.orchestrator.TaskFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluatorTest {
    // Output of a real Playwright run of an evaluation script (simulated locally in this session).
    private val realRun = """{"criteria":[{"name":"Butona basınca skor 1 olur","pass":true,"evidence":"skor=1"},
        {"name":"İkinci basışta skor 2","pass":true,"evidence":"skor=2"},
        {"name":"Liderlik tablosu görünür","pass":false,"evidence":"Tablo bulunamadı: page.waitForSelector: Timeout 1500ms exceeded."}],
        "consoleErrors":["ikinci tık hatası"]}"""

    @Test fun hardThresholdAllCriteriaAndCleanConsole() {
        val r = Evaluator.parse(realRun)!!
        assertFalse(r.passed)
        assertEquals("2/3 ölçüt geçti, 1 konsol hatası", r.summary)
        val report = r.report()
        assertTrue(report.contains("FAIL: Liderlik tablosu görünür"))
        assertTrue(report.contains("CONSOLE: ikinci tık hatası"))
        assertFalse(report.contains("Butona basınca")) // only failures go to the reviewer
        assertTrue(Evaluator.Result(listOf(Evaluator.Criterion("a", true, "ok")), emptyList()).passed)
        assertFalse(Evaluator.Result(listOf(Evaluator.Criterion("a", true, "ok")), listOf("TypeError")).passed)
        assertFalse(Evaluator.Result(emptyList(), emptyList()).passed) // nothing checked is not a pass
        assertNull(Evaluator.parse("bozuk"))
    }

    @Test fun projectKindAndAppFolder() {
        assertEquals(Evaluator.Kind.WEB, Evaluator.detect(listOf("web/index.html", "web/js/engine.js", "tools/levels.src.mjs")))
        assertEquals(Evaluator.Kind.ANDROID, Evaluator.detect(listOf("app/build.gradle.kts", "app/src/main/AndroidManifest.xml", "docs/index.html")))
        assertEquals(Evaluator.Kind.NONE, Evaluator.detect(listOf("main.py", "README.md")))
        assertEquals("web", Evaluator.appDir(listOf("web/index.html", "web/sub/index.html", "node_modules/x/index.html")))
        assertEquals(".", Evaluator.appDir(listOf("index.html", "docs/index.html")))
        assertNull(Evaluator.appDir(listOf("package.json")))
    }

    @Test fun scriptExtractionAndContract() {
        val reply = "Here:\n```js\nimport { chromium } from 'playwright';\nfs.writeFileSync('results.json', '{}')\n```"
        assertTrue(Evaluator.extractCode(reply)!!.startsWith("import { chromium }"))
        assertNull(Evaluator.extractCode("```js\nconsole.log('no browser')\n```"))
        val plan = OrchestratorPlan(mode = OrchestratorMode.FULL_MULTI_AGENT, request = "r", repo = "me/web", branch = "feat/x", base = null,
            commitMessage = "m", tasks = listOf(
                SubTask("t1", "Skor", "d", listOf(TaskFile("a.js", "modify", "x")), acceptance = listOf("Skor artar", "Sıfırlanır")),
                SubTask("t2", "Tablo", "d", listOf(TaskFile("b.js", "modify", "x"))),
                SubTask("r1-1", "Düzeltme", "d", listOf(TaskFile("a.js", "modify", "x")), acceptance = listOf("yok sayılır"), fix = true),
            ))
        assertEquals(listOf("Skor artar", "Sıfırlanır", "Tablo"), Evaluator.contract(plan))
    }

    @Test fun evaluateWorkflowIsMapped() {
        assertEquals(CloudWorkflowTemplates.EVALUATE, CloudWorkflowTemplates.forTool("playwright"))
        val y = CloudWorkflowTemplates.EVALUATE.yaml
        assertTrue(y.contains("playwright@1.47.2") && y.contains("--with-deps chromium"))
        assertTrue(y.contains("if: always()")) // results are uploaded even when the app misbehaves
        assertTrue(y.contains("python3 -m http.server 8080"))
    }
}
