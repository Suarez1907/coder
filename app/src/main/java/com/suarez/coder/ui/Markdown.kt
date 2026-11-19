package com.suarez.coder.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed class Block {
    data class Text(val text: String) : Block()
    data class Code(val lang: String, val code: String) : Block()
}

private fun splitBlocks(md: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = md.split("\n")
    var i = 0
    val buf = StringBuilder()
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            if (buf.isNotBlank()) out += Block.Text(buf.toString().trim('\n'))
            buf.clear()
            val lang = line.trimStart().removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.append(lines[i]).append('\n'); i++
            }
            out += Block.Code(lang, code.toString().trimEnd('\n'))
            i++
            continue
        }
        buf.append(line).append('\n')
        i++
    }
    if (buf.isNotBlank()) out += Block.Text(buf.toString().trim('\n'))
    return out
}

private fun inline(text: String, codeColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { li, raw ->
        var line = raw
        var heading = false
        val h = Regex("^#{1,6}\\s+").find(line)
        if (h != null) { heading = true; line = line.substring(h.value.length) }
        if (Regex("^\\s*[-*]\\s+").containsMatchIn(line)) line = line.replaceFirst(Regex("^(\\s*)[-*]\\s+"), "$1• ")
        if (heading) pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp))
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) && line.indexOf("**", i + 2) > 0 -> {
                    val end = line.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.substring(i + 2, end)) }
                    i = end + 2
                }
                line[i] == '`' && line.indexOf('`', i + 1) > 0 -> {
                    val end = line.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) { append(line.substring(i + 1, end)) }
                    i = end + 1
                }
                else -> { append(line[i]); i++ }
            }
        }
        if (heading) pop()
        if (li < lines.size - 1) append('\n')
    }
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val codeColor = MaterialTheme.colorScheme.secondary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in splitBlocks(md)) {
            when (b) {
                is Block.Text -> SelectionContainer { Text(inline(b.text, codeColor), style = MaterialTheme.typography.bodyMedium) }
                is Block.Code -> Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(b.lang.ifBlank { "kod" }, style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(b.code)) }) { Text("Kopyala") }
                        }
                        SelectionContainer {
                            Text(
                                b.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
