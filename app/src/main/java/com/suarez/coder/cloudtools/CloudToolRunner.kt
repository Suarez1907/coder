package com.suarez.coder.cloudtools

import com.suarez.coder.apk.ApkDownloader
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * run_cloud_tool: makes sure the workflow exists in the repo, starts it with workflow_dispatch, waits, and returns
 * either the cleaned traceback (so the agent can fix its script) or the downloaded outputs (preview attached to chat).
 * Everything runs in GitHub Actions; nothing on the phone or any other computer.
 */
class CloudToolRunner(
    private val github: GitHub,
    private val downloader: ApkDownloader,
    private val outDir: File,
    private val ciErrors: suspend (repo: String, runId: Long) -> String,
    private val onStatus: (String?) -> Unit = {},
    private val pollMs: Long = 15_000,
) {
    data class Install(val tool: CloudWorkflowTemplates.CloudTool, val state: CloudWorkflowTemplates.InstallState)

    companion object {
        const val MAX_WAIT_MS = 35 * 60_000L
        const val MAX_UNZIP_BYTES = 300L * 1024 * 1024
        private val MARKER = Regex("\\[\\[cloud-output tool=(\\w+) run=(\\d+) dir=([^\\]]+)]]")

        /** The line the chat UI turns into a result card. */
        fun marker(tool: String, runId: Long, dir: File) = "[[cloud-output tool=$tool run=$runId dir=${dir.absolutePath}]]"

        data class Output(val tool: String, val runId: Long, val dir: File) {
            val files get() = dir.walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
            val preview get() = files.firstOrNull { it.name.equals("preview.png", true) } ?: files.firstOrNull { it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            val assets get() = files.filter { it.extension.lowercase() in setOf("glb", "gltf", "blend", "fbx", "obj", "mp4", "gif", "wav", "mp3") }
        }

        fun outputs(text: String): List<Output> = MARKER.findAll(text).map { Output(it.groupValues[1], it.groupValues[2].toLong(), File(it.groupValues[3])) }.toList()

        /** Repo-relative script path: no absolute paths, no "..". */
        fun cleanPath(p: String): String? {
            val c = p.trim().removePrefix("./").trim('/')
            if (c.isBlank() || c.split('/').any { it == ".." } || c.startsWith("~")) return null
            return c
        }

        /** Extracts a zip without letting entries escape [dir] (zip-slip) or exceed [maxBytes]. */
        fun unzip(zip: File, dir: File, maxBytes: Long = MAX_UNZIP_BYTES): List<File> {
            dir.mkdirs()
            val root = dir.canonicalFile
            val out = ArrayList<File>()
            var total = 0L
            ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val f = File(root, e.name).canonicalFile
                    if (!f.path.startsWith(root.path + File.separator)) continue
                    f.parentFile?.mkdirs()
                    f.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw ToolException("Artifact çok büyük (>${maxBytes / 1024 / 1024} MB).")
                            o.write(buf, 0, n)
                        }
                    }
                    out += f
                }
            }
            return out
        }
    }

    suspend fun installState(repo: String, ref: String?): List<Install> = CloudWorkflowTemplates.ALL.map { t ->
        Install(t, CloudWorkflowTemplates.state(t, runCatching { github.readFileRaw(repo, t.path, ref) }.getOrNull()))
    }

    /** Writes (or overwrites, when [force]) the workflow file on [branch]. */
    suspend fun install(repo: String, tool: CloudWorkflowTemplates.CloudTool, branch: String?, force: Boolean): String {
        val b = branch?.takeIf { it.isNotBlank() } ?: github.defaultBranch(repo)
        val current = runCatching { github.readFileRaw(repo, tool.path, b) }.getOrNull()
        if (current != null && !force) return "${tool.file} zaten var ($b)."
        if (current?.trim() == tool.yaml.trim()) return "${tool.file} güncel ($b)."
        github.commitFiles(repo, b, "Coder: ${tool.title} bulut aracı (${tool.file})", listOf(GitHub.FileChange(tool.path, tool.yaml, false)), null)
        return "${tool.file} ${if (current == null) "kuruldu" else "güncellendi"} ($b)."
    }

    suspend fun run(repo: String, tool: String, scriptPath: String, outputDir: String?, branch: String?, blenderVersion: String?): String {
        val t = CloudWorkflowTemplates.forTool(tool) ?: throw ToolException("Bilinmeyen bulut aracı: $tool (blender veya toolbox).")
        val script = cleanPath(scriptPath) ?: throw ToolException("Geçersiz script_path: $scriptPath (repo içi göreli yol olmalı).")
        val out = outputDir?.let { cleanPath(it) } ?: "coder-out"
        val base = github.defaultBranch(repo)
        val ref = branch?.takeIf { it.isNotBlank() } ?: base
        if (runCatching { github.readFileRaw(repo, script, ref) }.getOrNull() == null) {
            throw ToolException("$script, $ref dalında yok. Önce betiği commit et, sonra run_cloud_tool çağır.")
        }
        // workflow_dispatch needs the file on the default branch, and on the branch it runs from.
        val installed = listOf(base, ref).distinct().map { install(repo, t, it, force = false) }

        val inputs = JSONObject().put("script_path", script).put("output_dir", out)
        if (t == CloudWorkflowTemplates.BLENDER && !blenderVersion.isNullOrBlank()) inputs.put("blender_version", blenderVersion)
        val since = System.currentTimeMillis()
        onStatus("${t.title} bulutta başlatılıyor")
        github.dispatchWorkflow(repo, t.file, ref, inputs)
        var run: GitHub.Run? = null
        for (i in 0 until 24) {
            delay(5_000)
            run = github.dispatchedRun(repo, t.file, ref, since)
            if (run != null) break
        }
        run ?: throw ToolException("${t.file} çalıştırması 2 dakikada başlamadı. Actions sekmesini kontrol edin.")
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (run!!.status != "completed") {
            if (System.currentTimeMillis() > deadline) return "Zaman aşımı: ${t.title} 35 dakikada bitmedi (${run.url})."
            onStatus("${t.title} bulutta çalışıyor (${run.status})")
            delay(pollMs)
            run = github.run(repo, run.id)
        }
        onStatus(null)
        val head = installed.joinToString(" ") + "\n${t.title} çalıştırması: ${run.conclusion} (${run.url})\n"
        if (run.conclusion != "success") {
            return head + "BAŞARISIZ. Hatayı betikte düzelt (github_apply_diff), sonra run_cloud_tool'u tekrar çağır.\n\n" + ciErrors(repo, run.id)
        }
        val art = github.artifacts(repo, run.id).firstOrNull { it.second == t.artifact }
            ?: return head + "Başarılı ama '${t.artifact}' artifact'i yok: betik çıktıları $out altına yazmalı (CODER_OUTPUT_DIR)."
        onStatus("Çıktılar indiriliyor")
        val dir = File(outDir, "${repo.replace('/', '_')}-${run.id}")
        val zip = File(outDir, "artifact-${run.id}.zip")
        try {
            outDir.mkdirs()
            downloader.fetch("https://api.github.com/repos/$repo/actions/artifacts/${art.first}/zip", "application/vnd.github+json", zip) {}
            val files = unzip(zip, dir)
            onStatus(null)
            return head + "Çıktılar indirildi: " + files.joinToString { "${it.name} (${it.length() / 1024} KB)" } +
                ". Önizleme sohbete eklendi; kullanıcı görseli görebilir.\n" + marker(t.id, run.id, dir)
        } finally {
            zip.delete()
            onStatus(null)
        }
    }
}
