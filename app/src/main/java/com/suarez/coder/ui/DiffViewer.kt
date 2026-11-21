package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.suarez.coder.diff.DiffFile
import com.suarez.coder.diff.DiffLine
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
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Kapat") }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Diff", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${files.size} dosya", style = Mono.tiny, color = coder.muted)
                            CountPill("+$added", green)
                            CountPill("−$removed", red)
                        }
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(DiffLines.toText(files))) }) { Text("Kopyala") }
                }
                HorizontalDivider(color = coder.border)
                // One vertical + one horizontal scroll for the whole diff keeps long lines aligned.
                SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        files.forEach { f -> DiffFileCard(f, red, green) }
                    }
                }
            }
        }
    }
}

/** Small rounded +added / -removed counter chip. */
@Composable
private fun CountPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.16f)) {
        Text(text, style = Mono.tiny, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** A crisp, dark-IDE style file card with a distinct path header bar. */
@Composable
private fun DiffFileCard(f: DiffFile, red: Color, green: Color) {
    val fileAdded = f.blocks.sumOf { b -> b.count { it.kind == '+' } }
    val fileRemoved = f.blocks.sumOf { b -> b.count { it.kind == '-' } }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = coder.codeBg,
        border = BorderStroke(1.dp, coder.border),
        modifier = Modifier.widthIn(min = 2000.dp).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(coder.codeHeader).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(f.path, style = Mono.small, color = coder.architect, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text("+$fileAdded", style = Mono.tiny, color = green)
                Text("−$fileRemoved", style = Mono.tiny, color = red)
            }
            HorizontalDivider(color = coder.border)
            f.blocks.forEachIndexed { k, block ->
                if (f.blocks.size > 1) {
                    Text("@@ blok ${k + 1}", style = Mono.tiny, color = coder.muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                block.forEach { l -> DiffLineRow(l, red, green) }
            }
        }
    }
}

/** One diff line with a status strip, dual line-number gutter and translucent highlight. */
@Composable
private fun DiffLineRow(l: DiffLine, red: Color, green: Color) {
    val strip = when (l.kind) { '+' -> green; '-' -> red; else -> Color.Transparent }
    val bg = when (l.kind) { '+' -> green.copy(alpha = 0.12f); '-' -> red.copy(alpha = 0.12f); else -> Color.Transparent }
    val fg = when (l.kind) { '+' -> green; '-' -> red; else -> MaterialTheme.colorScheme.onSurface }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(bg), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(strip))
        Text((l.oldNo?.toString() ?: "").padStart(4), style = Mono.tiny, color = coder.muted,
            modifier = Modifier.width(40.dp).padding(start = 8.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(coder.border))
        Text((l.newNo?.toString() ?: "").padStart(4), style = Mono.tiny, color = coder.muted,
            modifier = Modifier.width(40.dp).padding(start = 8.dp))
        Text(" ${l.kind} ${l.text}", style = Mono.small, color = fg, softWrap = false)
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
