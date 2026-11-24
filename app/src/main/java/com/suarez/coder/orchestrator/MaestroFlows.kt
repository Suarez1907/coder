package com.suarez.coder.orchestrator

/**
 * Android acceptance checks as Maestro flows: one small YAML flow per criterion, run on the emulator in GitHub
 * Actions (the Android counterpart of the Playwright evaluator). Results come back as a JUnit report.
 */
object MaestroFlows {
    const val SYSTEM = "You write Maestro (mobile.dev) UI test flows for an Android app, ONE flow per acceptance criterion. Each flow " +
        "is a separate ```yaml block: first `appId: <id>` then a line `---`, then commands. Start with `- launchApp:` (with " +
        "`clearState: true` when the criterion starts from scratch). Use only: launchApp, tapOn (text or id), inputText, eraseText, " +
        "assertVisible, assertNotVisible, scrollUntilVisible, back, pressKey, waitForAnimationToEnd, takeScreenshot. Match " +
        "on-screen text exactly as the code shows it (string resources, Text(...) calls); use `.*part.*` regexes only when the text " +
        "is dynamic. End each flow with `- takeScreenshot: criterion_<n>`. Add a `# <criterion>` comment as the first line of the " +
        "block. No other text."

    data class Flow(val name: String, val yaml: String)

    /** The YAML blocks of the answer that look like runnable flows for [appId]. */
    fun extract(reply: String, appId: String): List<Flow> =
        Regex("```(?:yaml|yml)?\\s*\\n([\\s\\S]*?)```").findAll(reply).map { it.groupValues[1].trim() }
            .filter { it.contains("---") && it.contains("launchApp") }
            .mapIndexed { i, y ->
                val fixed = if (Regex("(?m)^appId:").containsMatchIn(y)) y.replace(Regex("(?m)^appId:.*$"), "appId: $appId") else "appId: $appId\n$y"
                val title = Regex("(?m)^#\\s*(.+)$").find(fixed)?.groupValues?.get(1)?.trim()?.take(120) ?: "Ölçüt ${i + 1}"
                // The flow's name is what Maestro's report shows for it (instead of the file name).
                val named = if (Regex("(?m)^name:").containsMatchIn(fixed.substringBefore("\n---"))) fixed
                    else fixed.replaceFirst(Regex("(?m)^(appId:.*)$"), "\$1\nname: " + Regex.escapeReplacement(quote(title)))
                Flow(title, named + "\n")
            }.take(8).toList()

    /** A YAML double-quoted scalar (only the escapes YAML 1.1 knows). */
    fun quote(s: String) = "\"" + s.filter { it >= ' ' }.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** applicationId from the app module's build file. */
    fun appId(buildFiles: Map<String, String>): String? = buildFiles.values.firstNotNullOfOrNull { t ->
        Regex("applicationId\\s*(?:=\\s*)?[\"']([\\w.]+)[\"']").find(t)?.groupValues?.get(1)
    }

    data class Case(val name: String, val pass: Boolean, val message: String)

    /** Test cases of Maestro's JUnit report. */
    fun junit(xml: String): List<Case> = Regex("<testcase\\b([^>]*?)(/>|>([\\s\\S]*?)</testcase>)").findAll(xml).map { m ->
        val attrs = m.groupValues[1]
        val name = Regex("name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1).orEmpty()
        val body = m.groupValues[3]
        val fail = Regex("<(failure|error)\\b[^>]*?(?:message=\"([^\"]*)\")?[^>]*>([\\s\\S]*?)</\\1>|<(failure|error)\\b[^>]*?message=\"([^\"]*)\"[^>]*/>").find(body)
        Case(name, fail == null, fail?.let { (it.groupValues[2] + " " + it.groupValues[3] + it.groupValues[5]).trim().take(400) }.orEmpty())
    }.toList()
}
