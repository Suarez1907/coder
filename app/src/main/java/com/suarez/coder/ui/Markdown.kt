package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private fun inline(
    text: String,
    codeColor: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { li, raw ->
        var line = raw
        var heading = 0
        val h = Regex("^#{1,6}\\s+").find(line)
        if (h != null) { heading = h.value.trim().length; line = line.substring(h.value.length) }
        var quote = false
        if (Regex("^\\s*>\\s?").containsMatchIn(line)) { quote = true; line = line.replaceFirst(Regex("^\\s*>\\s?"), "") }
        if (Regex("^\\s*[-*]\\s+").containsMatchIn(line)) line = line.replaceFirst(Regex("^(\\s*)[-*]\\s+"), "$1• ")
        if (heading > 0) pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = when { heading <= 1 -> 19.sp; heading == 2 -> 17.sp; else -> 15.sp }))
        if (quote) {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append("▏ ") }
            pushStyle(SpanStyle(color = codeColor.copy(alpha = 0.9f)))
        }
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
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, background = codeBg)) {
                        append(" " + line.substring(i + 1, end) + " ")
                    }
                    i = end + 1
                }
                else -> { append(line[i]); i++ }
            }
        }
        if (quote) pop()
        if (heading > 0) pop()
        if (li < lines.size - 1) append('\n')
    }
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val codeColor = MaterialTheme.colorScheme.secondary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in splitBlocks(md)) {
            when (b) {
                is Block.Text -> SelectionContainer {
                    Text(inline(b.text, codeColor, coder.codeBg, coder.architect), style = MaterialTheme.typography.bodyMedium)
                }
                is Block.Code -> Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = coder.codeBg,
                    border = BorderStroke(1.dp, coder.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().background(coder.codeHeader).padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(b.lang.ifBlank { "code" }, style = Mono.tiny, color = coder.muted)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = coder.border.copy(alpha = 0.45f),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { clipboard.setText(AnnotatedString(b.code)) },
                            ) {
                                Text("Kopyala", style = Mono.tiny, color = coder.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                        HorizontalDivider(color = coder.border, thickness = 1.dp)
                        SelectionContainer {
                            Text(
                                b.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
