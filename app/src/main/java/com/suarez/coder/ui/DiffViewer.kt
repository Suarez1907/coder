package com.suarez.coder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.suarez.coder.diff.DiffFile
import com.suarez.coder.diff.DiffLines

/** Full-screen, colour-coded view of SEARCH/REPLACE blocks (or written files). */
@Composable
fun DiffViewer(files: List<DiffFile>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val added = files.sumOf { f -> f.blocks.sumOf { b -> b.count { it.kind == '+' } } }
    val removed = files.sumOf { f -> f.blocks.sumOf { b -> b.count { it.kind == '-' } } }
    val red = coder.warn
    val green = coder.cacheGood
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SystemBarsEffect()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Kapat") }
                    Icon(Icons.Rounded.Difference, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Diff", style = MaterialTheme.typography.titleMedium)
                        Row {
                            Text("${files.size} dosya · ", style = Mono.tiny, color = coder.muted)
                            Text("+$added", style = Mono.tiny, color = green)
                            Text(" −$removed", style = Mono.tiny, color = red)
                        }
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(DiffLines.toText(files))) }) { Text("Kopyala") }
                }
                HorizontalDivider(color = coder.border)
                // One vertical + one horizontal scroll for the whole diff keeps long lines aligned.
                SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        files.forEach { f ->
                            Text(f.path, style = Mono.small, color = coder.architect, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            f.blocks.forEachIndexed { k, block ->
                                if (f.blocks.size > 1) Text("@@ blok ${k + 1}", style = Mono.tiny, color = coder.muted, modifier = Modifier.padding(horizontal = 12.dp))
                                block.forEach { l ->
                                    val bg = when (l.kind) { '-' -> red.copy(alpha = 0.12f); '+' -> green.copy(alpha = 0.12f); else -> MaterialTheme.colorScheme.background }
                                    val fg = when (l.kind) { '-' -> red; '+' -> green; else -> MaterialTheme.colorScheme.onSurface }
                                    Row(Modifier.background(bg).widthIn(min = 2000.dp).padding(horizontal = 8.dp)) {
                                        Text((l.oldNo?.toString() ?: "").padStart(4), style = Mono.tiny.copy(fontFeatureSettings = "tnum"), color = coder.muted)
                                        Spacer(Modifier.width(4.dp))
                                        Text((l.newNo?.toString() ?: "").padStart(4), style = Mono.tiny.copy(fontFeatureSettings = "tnum"), color = coder.muted)
                                        Spacer(Modifier.width(8.dp))
                                        Text("${l.kind} ${l.text}", style = Mono.small, color = fg, softWrap = false)
                                    }
                                }
                                Spacer(Modifier.size(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Diff files for a tool call, or null when the call has nothing to show as a diff. */
fun diffFilesFor(toolName: String, args: org.json.JSONObject): List<DiffFile>? = when (toolName) {
    "github_apply_diff" -> args.optString("diff").takeIf { it.isNotBlank() }?.let { DiffLines.fromSearchReplace(it) }?.takeIf { it.isNotEmpty() }
    "github_write_files" -> args.optJSONArray("files")?.let { arr ->
        DiffLines.fromWrittenFiles((0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { f -> f.optString("path") to (if (f.optBoolean("delete")) null else f.optString("content")) }
        })
    }
    else -> null
}

@Composable
fun rememberDiffFiles(text: String?): List<DiffFile>? = remember(text) {
    text?.takeIf { it.contains("<<<<<<< SEARCH") }?.let { DiffLines.fromSearchReplace(it) }?.takeIf { it.isNotEmpty() }
}
