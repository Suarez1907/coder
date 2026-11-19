package com.suarez.coder.agent

import com.suarez.coder.data.Provider
import com.suarez.coder.data.Settings
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.GeminiClient
import com.suarez.coder.llm.LlmClient
import com.suarez.coder.llm.OpenAiCompatClient
import com.suarez.coder.llm.ToolCall
import com.suarez.coder.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Agent(private val settings: Settings) {
    val tools = ToolRegistry(settings)

    fun client(provider: Provider = settings.provider): LlmClient = when (provider) {
        Provider.GEMINI -> GeminiClient(settings.geminiKey, settings.geminiModel)
        Provider.DEEPSEEK -> OpenAiCompatClient("DeepSeek", settings.deepseekBaseUrl, settings.deepseekKey, settings.deepseekModel)
    }

    private fun systemPrompt(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val repo = settings.defaultRepo.takeIf { it.isNotBlank() }
        return buildString {
            appendLine("You are Coder, an autonomous software engineering agent running inside an Android app.")
            appendLine("The user works only from a phone, with no computer. You act through tools:")
            appendLine("- GitHub: browse repos, read files, commit changes, create branches and pull requests, and call any GitHub REST endpoint (issues, Actions runs and logs, releases, creating repos).")
            appendLine("- Web: search the internet and fetch pages (documentation, APIs, error messages).")
            appendLine("You cannot run code on the device. To build or test, rely on the repository's GitHub Actions: after pushing, check runs with github_api GET /repos/{repo}/actions/runs and read failed job logs, then fix the errors.")
            appendLine()
            appendLine("How to work:")
            appendLine("1. Understand before changing: list the tree, read the relevant files. Never guess file contents.")
            appendLine("2. For small edits in existing files use github_edit_file; for new files or rewrites use github_write_files with the COMPLETE file content. Group related changes into one commit.")
            appendLine("3. Write complete, working code, never placeholders like '...rest unchanged'.")
            appendLine("4. Use web_search / web_fetch when you need current information or documentation instead of guessing.")
            appendLine("5. Unless the user says otherwise, commit to the branch they name; if none is named, ask or use a new feature branch and offer a pull request.")
            appendLine("6. When done, briefly summarize what you changed with links. Keep answers short and readable on a phone screen.")
            appendLine("Reply in the user's language (default: Turkish).")
            appendLine()
            appendLine("Today's date: $date")
            if (repo != null) appendLine("Default repository: $repo (use it when the user does not name another).")
            if (settings.customInstructions.isNotBlank()) {
                appendLine()
                appendLine("User's custom instructions:")
                appendLine(settings.customInstructions)
            }
        }
    }

    /**
     * Runs the tool-use loop until the model answers without tool calls.
     * [history] is extended in place; [onUpdate] is called after every change.
     */
    suspend fun run(
        history: MutableList<ChatMessage>,
        onUpdate: () -> Unit,
        approve: suspend (ToolCall) -> Boolean,
    ) {
        val llm = client()
        val system = systemPrompt()
        val maxSteps = settings.maxSteps
        for (step in 1..maxSteps) {
            val reply = llm.complete(system, history.filter { it !is ChatMessage.Notice }, tools.specs)
            history += reply
            onUpdate()
            if (reply.toolCalls.isEmpty()) return

            for (call in reply.toolCalls) {
                val result = try {
                    if (tools.needsApproval(call) && !settings.autoApprove && !approve(call)) {
                        ChatMessage.ToolResult(call.id, call.name, "Kullanıcı bu işlemi reddetti. Ne yapmak istediğini kullanıcıya sor.", isError = true)
                    } else {
                        ChatMessage.ToolResult(call.id, call.name, tools.execute(call))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ChatMessage.ToolResult(call.id, call.name, e.message ?: e.toString(), isError = true)
                }
                history += result
                onUpdate()
            }
        }
        history += ChatMessage.Notice("Adım sınırına ($maxSteps) ulaşıldı. Devam etmek için \"devam et\" yazın.")
        onUpdate()
    }
}
