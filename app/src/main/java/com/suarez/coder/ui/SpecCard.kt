package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suarez.coder.cost.Pricing
import com.suarez.coder.session.SpecCardModel

/** Compact spec the user can trim before a large task starts. Monochrome, no emoji. */
@Composable
fun SpecCard(card: SpecCardModel, onApprove: (disabled: Set<String>, note: String) -> Unit, onCancel: () -> Unit) {
    var disabled by remember(card) { mutableStateOf(setOf<String>()) }
    var note by remember(card) { mutableStateOf(card.note) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, coder.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Spec", style = Mono.small, fontWeight = FontWeight.SemiBold, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
            Text(
                "${card.risk} · ${Pricing.formatUsd(card.costLowUsd)}–${Pricing.formatUsd(card.costHighUsd)} · Mimar kilitli",
                style = Mono.tiny,
                color = coder.muted,
            )
            card.files.take(8).forEach { path ->
                Text(path, style = Mono.tiny.copy(fontFamily = FontFamily.Monospace), color = coder.muted, maxLines = 1)
            }
            card.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.id !in disabled,
                        onCheckedChange = { on -> disabled = if (on) disabled - item.id else disabled + item.id },
                        colors = CheckboxDefaults.colors(
                            checkedColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                            uncheckedColor = coder.muted,
                            checkmarkColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Text(item.title, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            BasicTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                textStyle = Mono.tiny.copy(color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(androidx.compose.material3.MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                decorationBox = { inner ->
                    if (note.isEmpty()) Text("Tek satır not", style = Mono.tiny, color = coder.muted)
                    inner()
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text("Vazgeç", style = Mono.small, color = coder.muted) }
                TextButton(onClick = { onApprove(disabled, note.lineSequence().firstOrNull().orEmpty()) }) {
                    Text("Onayla ve Başlat", style = Mono.small, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
