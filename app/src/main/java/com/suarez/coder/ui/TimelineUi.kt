package com.suarez.coder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suarez.coder.agent.Span
import com.suarez.coder.agent.TimelineReport
import com.suarez.coder.cost.Pricing

/** Where the last task's time and money went: totals per kind, the slowest steps, and every step as a bar. */
@Composable
fun TimelineDialog(spans: List<Span>, onDismiss: () -> Unit) {
    val (run, steps) = remember(spans) { TimelineReport.lastRun(spans) }
    val totals = remember(steps) { TimelineReport.totals(steps) }
    val maxMs = (steps.maxOfOrNull { it.ms } ?: 1L).coerceAtLeast(1L)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zaman çizelgesi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (steps.isEmpty()) { Text("Bu sohbette kayıtlı adım yok.", color = coder.muted); return@Column }
                run?.let { Text("${it.name} · toplam ${TimelineReport.secs(it.ms)}", style = MaterialTheme.typography.bodyMedium) }
                totals.forEach { t ->
                    val share = run?.ms?.takeIf { it > 0 }?.let { " (%" + (t.ms * 100 / it) + ")" } ?: ""
                    Text("${Span.label(t.kind)}: ${TimelineReport.secs(t.ms)}$share · ${t.count} adım" +
                        (if (t.cost > 0) " · ${Pricing.formatUsd(t.cost)}" else ""), style = Mono.small)
                }
                HorizontalDivider(color = coder.border)
                Text("En yavaş adımlar", style = MaterialTheme.typography.labelLarge, color = coder.muted)
                TimelineReport.slowest(steps).forEach { Text("${TimelineReport.secs(it.ms)} · ${it.name}", style = Mono.small, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                HorizontalDivider(color = coder.border)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(steps) { s ->
                        Column(Modifier.padding(vertical = 3.dp)) {
                            Text("${TimelineReport.secs(s.ms)} · ${s.name}" + (if (!s.ok) " · hata" else ""), style = Mono.tiny,
                                color = if (s.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width((240f * s.ms / maxMs).coerceAtLeast(2f).dp).height(6.dp)
                                    .background(if (s.kind == Span.MODEL) coder.architect else coder.muted, RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}
