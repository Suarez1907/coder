package com.suarez.coder.docs

import com.suarez.coder.cloudtools.CloudToolRunner
import com.suarez.coder.tools.GitHub
import com.suarez.coder.tools.ToolException
import org.json.JSONObject
import java.io.File

/**
 * Converts a document the phone cannot read (PDF, .doc, epub, ...) with markitdown in GitHub Actions. The file is
 * committed to a throwaway branch of the active repo (private repos only), converted, and the branch is deleted.
 */
class DocsConverter(private val gh: GitHub, private val runner: CloudToolRunner) {
    companion object {
        fun safeName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(80).ifBlank { "belge" }
    }

    suspend fun convert(repo: String, name: String, bytes: ByteArray): String {
        if (repo.isBlank()) throw ToolException("Bu belge türü bulutta dönüştürülür; önce bir repo seçin.")
        val info = JSONObject(gh.raw("GET", "/repos/$repo", null).substringAfter('\n'))
        if (!info.optBoolean("private", false)) {
            throw ToolException("$repo herkese açık; belge oraya yüklenmez. Özel bir repo seçin ya da belgeyi .docx/.txt olarak ekleyin.")
        }
        val branch = "coder-docs-${System.currentTimeMillis() % 1_000_000}"
        val path = ".coder/docs/" + safeName(name)
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        try {
            gh.commitFiles(repo, branch, "coder: geçici belge (dönüştürülüp silinir)", listOf(GitHub.FileChange(path, b64, false, base64 = true)), null)
            val res = runner.run(repo, "docs", path, "coder-out", branch, null)
            val out = CloudToolRunner.outputs(res).firstOrNull() ?: throw ToolException(res.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n").take(600))
            val md = File(out.dir, "document.md")
            if (!md.isFile) throw ToolException("Dönüştürme çıktısı yok.")
            return md.readText().also { out.dir.deleteRecursively() }
        } finally {
            runCatching { gh.deleteBranch(repo, branch) }
        }
    }
}
