package com.suarez.coder.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
    var expanded by remember(plan.id, plan.state) { mutableStateOf(plan.state != PlanState.DONE) }
    val done = plan.tasks.count { it.status == TaskStatus.DONE }
    val totalCost = plan.totalCost
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, coder.border, RoundedCornerShape(12.dp))
            .animateContentSize(),
    ) {
        if (plan.state == PlanState.DONE && !expanded) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CheckCircleOutline, null, tint = coder.cacheGood, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${plan.mode.label} · $done/${plan.tasks.size} görev · ${Pricing.formatUsd(totalCost)}",
                    style = Mono.small,
                    color = coder.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.ExpandMore, "Genişlet", tint = coder.muted, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { vm.dismissPlan() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, "Kapat", tint = coder.muted, modifier = Modifier.size(18.dp))
                }
            }
        } else {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountTree, null, tint = coder.architect, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Orkestratör · ${plan.mode.label}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${plan.repo}@${plan.branch} · $done/${plan.tasks.size} · ${plan.state.label}",
                            style = Mono.tiny, color = coder.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(Pricing.formatUsd(totalCost), style = Mono.small, color = coder.cost)
                    if (plan.state == PlanState.DONE) {
                        IconButton(onClick = { expanded = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.ExpandLess, "Daralt", tint = coder.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = { vm.dismissPlan() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, "Kapat", tint = coder.muted, modifier = Modifier.size(18.dp))
                    }
                }
                if (plan.state == PlanState.RUNNING || plan.state == PlanState.AWAITING_APPROVAL || plan.state == PlanState.PAUSED) {
                    val frac = if (plan.tasks.isEmpty()) 0f else done.toFloat() / plan.tasks.size
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)).background(coder.border)) {
                        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(coder.architect))
                    }
                }
                plan.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (plan.state == PlanState.PAUSED) coder.cost else coder.muted) }
                plan.tasks.forEach { t -> TaskRow(t) }
                if (plan.roleCosts.isNotEmpty()) {
                    Text(plan.roleCosts.entries.joinToString(" · ") { "${it.key} ${Pricing.formatUsd(it.value)}" },
                        style = Mono.tiny, color = coder.muted)
                }
                if (plan.mode == OrchestratorMode.FULL_MULTI_AGENT && plan.reviewRound > 0) {
                    Text("İnceleme turu: ${plan.reviewRound}/${vm.settings.orchMaxReviewRounds}", style = Mono.tiny, color = coder.muted)
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
    }
    if (editing) PlanEditor(plan, onSave = { vm.editPlan(it); editing = false }, onDismiss = { editing = false })
}

@Composable
private fun TaskRow(t: SubTask) {
    val color = when (t.status) {
        TaskStatus.DONE -> coder.cacheGood
        TaskStatus.RUNNING -> coder.architect
        TaskStatus.FAILED -> coder.warn
        else -> coder.muted
    }
    val icon = when (t.status) {
        TaskStatus.DONE -> Icons.Rounded.CheckCircleOutline
        TaskStatus.RUNNING -> Icons.Rounded.PlayCircleOutline
        TaskStatus.FAILED -> Icons.Rounded.ErrorOutline
        TaskStatus.SKIPPED -> Icons.Rounded.RemoveCircleOutline
        else -> Icons.Rounded.RadioButtonUnchecked
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text((if (t.fix) "onarım · " else "") + "${t.id} · ${t.title}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(
                t.ownedFiles.joinToString { it.substringAfterLast('/') },
                t.dependsOn.takeIf { it.isNotEmpty() }?.let { "← ${it.joinToString()}" },
                if (t.requiresCi) "CI" else null,
                t.costUsd.takeIf { it > 0 }?.let { Pricing.formatUsd(it) },
                t.status.label,
            ).joinToString(" · "), style = Mono.tiny, color = coder.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            t.error?.takeIf { t.status == TaskStatus.FAILED || t.status == TaskStatus.PENDING }?.let {
                Text(it.lines().first().take(160), style = Mono.tiny, color = coder.warn, maxLines = 2)
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
