package com.suarez.coder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suarez.coder.agent.QueuedTask

/** Unattended task queue: add, run now, see the PR each task opened, open its chat. */
@Composable
fun QueueDialog(vm: ChatViewModel, onDismiss: () -> Unit, onOpenSession: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshQueue() }
    var text by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf(vm.agent.tools.activeRepo()) }
    val s = vm.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("İş kuyruğu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (s.queueRequiresCharging) "Şarjda" else "Her zaman") + (if (s.queueRequiresWifi) " ve Wi-Fi'da" else "") +
                    " sırayla çalışır. Her görev kendi coder/q-* dalında çalışır, PR açar, merge etmez; görev başı bütçe " +
                    String.format(java.util.Locale.US, "%.2f $", s.queueTaskBudget) + ".",
                    style = MaterialTheme.typography.bodySmall, color = coder.muted)
                OutlinedTextField(text, { text = it }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth(), label = { Text("Görev") })
                OutlinedTextField(repo, { repo = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Repo (sahip/isim)") })
                Row {
                    TextButton(onClick = { vm.enqueue(text, repo); text = "" }, enabled = text.isNotBlank()) { Text("Sıraya ekle") }
                    TextButton(onClick = { vm.runQueueNow() }, enabled = vm.queue.any { it.state == QueuedTask.State.QUEUED }) { Text("Şimdi çalıştır") }
                }
                HorizontalDivider(color = coder.border)
                if (vm.queue.isEmpty()) Text("Kuyruk boş.", style = MaterialTheme.typography.bodySmall, color = coder.muted)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(vm.queue.asReversed(), key = { it.id }) { t ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = t.sessionId.isNotBlank()) { onOpenSession(t.sessionId) }.padding(vertical = 6.dp)) {
                            Text(t.text.lineSequence().first(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${t.state.label} · ${t.repo}" + (if (t.source.startsWith("issue:")) " · issue" else ""),
                                style = Mono.tiny, color = coder.muted)
                            if (t.note.isNotBlank()) Text(t.note, style = Mono.tiny, color = coder.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (t.state == QueuedTask.State.FAILED) TextButton(onClick = { vm.requeue(t.id) }) { Text("Tekrar dene") }
                                if (t.state != QueuedTask.State.RUNNING) TextButton(onClick = { vm.removeQueued(t.id) }) { Text("Kaldır") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}
