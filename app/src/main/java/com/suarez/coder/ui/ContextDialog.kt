package com.suarez.coder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
        SystemBarsEffect()
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bağlam dosyaları", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Seçilen dosyalar her isteğe eklenir (Gemini'de önbelleğe alınır), model onları tekrar okumaz. " +
                        "Yalnızca üzerinde çalıştığın birkaç dosyayı seç. build, node_modules, lock, binary ve .gitignore dosyaları listelenmez.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(repo, { repo = it }, label = { Text("Repo (sahip/isim)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref, { ref = it }, label = { Text("Branch (boşsa varsayılan)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            status = "Yükleniyor…"
                            status = runCatching {
                                val list = vm.loadTree(repo, ref)
                                all.clear(); all.addAll(list)
                                "${list.size} dosya"
                            }.getOrElse { it.message ?: "Hata" }
                        }
                    }) { Text("Dosyaları yükle") }
                    TextButton(onClick = { selected.clear() }) { Text("Temizle") }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    query, { query = it },
                    label = { Text("Ara") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Seçili: ${selected.size}", style = Mono.small.copy(fontFeatureSettings = "tnum"), color = coder.muted)
                    val capMax = all.size.coerceAtLeast(1)
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.06f))) {
                        Box(
                            Modifier.fillMaxWidth((selected.size.toFloat() / capMax).coerceIn(0f, 1f)).height(4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(coder.architect),
                        )
                    }
                }
                val shown = (selected + all.filter { it !in selected }).filter { query.isBlank() || it.contains(query, true) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(shown) { path ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (path in selected) selected.remove(path) else selected.add(path) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(path in selected, { if (it) selected.add(path) else selected.remove(path) })
                            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Vazgeç")
                    }
                    Button(onClick = { vm.saveContext(repo, ref, selected.toList()); onDismiss() }) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Kaydet")
                    }
                }
            }
        }
    }
}
