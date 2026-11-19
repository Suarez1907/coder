package com.suarez.coder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Lets the user pin a few repository files as context; filtered files are never offered. */
@Composable
fun ContextDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val s = vm.settings
    var repo by remember { mutableStateOf(s.contextRepo.ifBlank { s.defaultRepo }) }
    var ref by remember { mutableStateOf(s.contextRef) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val all = remember { mutableStateListOf<String>() }
    val selected = remember { mutableStateListOf<String>().apply { addAll(s.contextFiles) } }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "Bağlam Dosyaları",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            "${selected.size} dosya seçili",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Kapat")
                    }
                }

                Text(
                    "Seçilen dosyalar her isteğe eklenir (Gemini'de önbelleğe alınır), model onları tekrar okumaz. " +
                        "Yalnızca üzerinde çalıştığın birkaç dosyayı seç.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("Repo (sahip/isim)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ref,
                    onValueChange = { ref = it },
                    label = { Text("Branch (boşsa varsayılan)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            scope.launch {
                                status = "Yükleniyor…"
                                status = runCatching {
                                    val list = vm.loadTree(repo, ref)
                                    all.clear()
                                    all.addAll(list)
                                    "${list.size} dosya yüklendi"
                                }.getOrElse { it.message ?: "Hata" }
                            }
                        },
                    ) {
                        Text("Dosyaları yükle")
                    }
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { selected.clear() }) {
                            Text("Temizle")
                        }
                    }
                    if (status.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Dosya ara") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                val shown = (selected + all.filter { it !in selected }).filter { query.isBlank() || it.contains(query, true) }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    items(shown) { path ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (path in selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    if (path in selected) selected.remove(path) else selected.add(path)
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = path in selected,
                                    onCheckedChange = { if (it) selected.add(path) else selected.remove(path) },
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Vazgeç") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            vm.saveContext(repo, ref, selected.toList())
                            onDismiss()
                        },
                    ) {
                        Text("Kaydet (${selected.size})")
                    }
                }
            }
        }
    }
}
