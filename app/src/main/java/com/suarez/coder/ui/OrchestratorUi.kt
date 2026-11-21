package com.suarez.coder.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suarez.coder.cost.Pricing
import com.suarez.coder.orchestrator.OrchestratorMode
import com.suarez.coder.orchestrator.OrchestratorPlan
import com.suarez.coder.orchestrator.PlanState
import com.suarez.coder.orchestrator.SubTask
import com.suarez.coder.orchestrator.TaskStatus

/** Live card of the orchestrator plan: subtasks with status icons, per-agent cost and the controls. */
@Composable
fun PlanCard(vm: ChatViewModel, plan: OrchestratorPlan) {
    var editing by remember { mutableStateOf(false) }
    var expanded by remember(plan.id) { mutableStateOf(!plan.state.finished) }
    val accent = when (plan.state) {
        PlanState.RUNNING, PlanState.DONE -> coder.editor
        PlanState.FAILED, PlanState.CANCELLED, PlanState.PAUSED -> coder.warn
        else -> coder.architect
    }
    val done = plan.tasks.count { it.status == TaskStatus.DONE }
    val fraction = if (plan.tasks.isEmpty()) 0f else done.toFloat() / plan.tasks.size
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = coder.codeBg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text("🗂 ", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Orkestratör", style = MaterialTheme.typography.titleSmall)
                        Pill(plan.mode.label, coder.architect)
                        Pill(plan.state.label, accent)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("${plan.repo} · ${plan.branch}", coder.muted)
                        Pill("$done/${plan.tasks.size}", accent)
                    }
                }
                Pill(Pricing.formatUsd(plan.totalCost), coder.cost)
                Text(if (expanded) " ▴" else " ▾", color = coder.muted)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(coder.border)) {
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            }
            plan.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (plan.state == PlanState.PAUSED) coder.cost else coder.muted) }
            if (expanded) {
                plan.tasks.forEach { t -> TaskRow(t) }
                if (plan.roleCosts.isNotEmpty()) {
                    Text(plan.roleCosts.entries.joinToString(" · ") { "${it.key} ${Pricing.formatUsd(it.value)}" },
                        style = Mono.tiny, color = coder.muted)
                }
                if (plan.mode == OrchestratorMode.FULL_MULTI_AGENT && plan.reviewRound > 0) {
                    Text("İnceleme turu: ${plan.reviewRound}/${vm.settings.orchMaxReviewRounds}", style = Mono.tiny, color = coder.muted)
                }
            }
            val busy = vm.running
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (plan.state) {
                    PlanState.AWAITING_APPROVAL -> {
                        Button(onClick = { vm.startPlan() }, enabled = !busy) { Text("Planı Başlat") }
                        OutlinedButton(onClick = { editing = true }, enabled = !busy) { Text("Düzenle") }
                        TextButton(onClick = { vm.cancelPlan() }) { Text("İptal", color = coder.warn) }
                    }
                    PlanState.RUNNING -> {
                        OutlinedButton(onClick = { vm.pausePlan() }, enabled = !vm.pauseRequested) {
                            Text(if (vm.pauseRequested) "Bu adımdan sonra duracak" else "Duraklat")
                        }
                        TextButton(onClick = { vm.cancelPlan() }) { Text("İptal", color = coder.warn) }
                    }
                    PlanState.PAUSED -> {
                        Button(onClick = { vm.startPlan() }, enabled = !busy) { Text("Devam Et") }
                        OutlinedButton(onClick = { editing = true }, enabled = !busy) { Text("Düzenle") }
                        TextButton(onClick = { vm.cancelPlan() }) { Text("İptal", color = coder.warn) }
                    }
                    else -> TextButton(onClick = { vm.dismissPlan() }) { Text("Kartı kapat") }
                }
            }
        }
    }
    if (editing) PlanEditor(plan, onSave = { vm.editPlan(it); editing = false }, onDismiss = { editing = false })
}

/** Compact rounded status/tag chip used in the mission-control header and task rows. */
@Composable
private fun Pill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Text(text, style = Mono.tiny, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun TaskRow(t: SubTask) {
    val color = when (t.status) {
        TaskStatus.DONE -> coder.cacheGood
        TaskStatus.RUNNING -> coder.architect
        TaskStatus.FAILED -> coder.warn
        else -> coder.muted
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, coder.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Text(t.status.icon, color = color, style = Mono.body, modifier = Modifier.width(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((if (t.fix) "🔧 " else "") + "${t.id} · ${t.title}",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (t.ownedFiles.isNotEmpty() || t.dependsOn.isNotEmpty() || t.requiresCi) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        t.ownedFiles.take(3).forEach { f -> Pill(f.substringAfterLast('/'), coder.architect) }
                        t.dependsOn.takeIf { it.isNotEmpty() }?.let { Pill("← ${it.joinToString()}", coder.muted) }
                        if (t.requiresCi) Pill("CI", coder.cost)
                    }
                }
                Text(listOfNotNull(
                    Pricing.formatUsd(t.costUsd).takeIf { t.costUsd > 0 },
                    t.status.label,
                ).joinToString(" · "), style = Mono.tiny, color = coder.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                t.error?.takeIf { t.status == TaskStatus.FAILED || t.status == TaskStatus.PENDING }?.let {
                    Text(it.lines().first().take(160), style = Mono.tiny, color = coder.warn, maxLines = 2)
                }
            }
        }
    }
}

/** Skip subtasks or reword them before (re)starting. Finished subtasks are read-only. */
@Composable
private fun PlanEditor(plan: OrchestratorPlan, onSave: (OrchestratorPlan) -> Unit, onDismiss: () -> Unit) {
    var tasks by remember { mutableStateOf(plan.tasks) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Planı düzenle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tasks.forEach { t ->
                    val locked = t.status == TaskStatus.DONE
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = t.status != TaskStatus.SKIPPED, enabled = !locked, onCheckedChange = { on ->
                                tasks = tasks.map { if (it.id == t.id) it.copy(status = if (on) TaskStatus.PENDING else TaskStatus.SKIPPED) else it }
                            })
                            Text("${t.id} · ${t.title}", style = MaterialTheme.typography.bodyMedium,
                                color = if (t.status == TaskStatus.SKIPPED) coder.muted else Color.Unspecified)
                        }
                        if (!locked && t.status != TaskStatus.SKIPPED) {
                            OutlinedTextField(t.description, { d -> tasks = tasks.map { if (it.id == t.id) it.copy(description = d) else it } },
                                label = { Text("Açıklama") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Spacer(Modifier.width(1.dp))
                Text("Atlanan görevlere bağlı olanlar yine çalışır (atlanan görev bitmiş sayılır).", style = MaterialTheme.typography.bodySmall, color = coder.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(plan.copy(tasks = tasks)) }) { Text("Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
