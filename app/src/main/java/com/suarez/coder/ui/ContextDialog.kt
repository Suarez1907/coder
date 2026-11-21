package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = coder.codeBg,
            border = BorderStroke(1.dp, coder.border),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📎", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f)) {
                        Text("Bağlam dosyaları", style = MaterialTheme.typography.titleMedium)
                        Text("Seçili: ${selected.size}", style = Mono.tiny, color = coder.muted)
                    }
                }
                Text(
                    "Seçilen dosyalar her isteğe eklenir (Gemini'de önbelleğe alınır), model onları tekrar okumaz. " +
                        "Yalnızca üzerinde çalıştığın birkaç dosyayı seç. build, node_modules, lock, binary ve .gitignore dosyaları listelenmez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = coder.muted,
                )
                val fraction = (selected.size / 12f).coerceIn(0f, 1f)
                val meter = when {
                    fraction < 0.5f -> coder.editor
                    fraction < 0.8f -> coder.cost
                    else -> coder.warn
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(coder.border)) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(meter))
                }
                OutlinedTextField(repo, { repo = it }, label = { Text("Repo (sahip/isim)") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ref, { ref = it }, label = { Text("Branch (boşsa varsayılan)") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
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
                    Text(status, style = MaterialTheme.typography.bodySmall, color = coder.muted)
                }
                OutlinedTextField(query, { query = it }, label = { Text("Ara") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                val shown = (selected + all.filter { it !in selected }).filter { query.isBlank() || it.contains(query, true) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown) { path ->
                        val dir = path.split('/').dropLast(1).takeLast(2).joinToString("/")
                        val name = path.substringAfterLast('/')
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (path in selected) coder.architect.copy(alpha = 0.10f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, if (path in selected) coder.architect.copy(alpha = 0.4f) else coder.border),
                            modifier = Modifier.fillMaxWidth().clickable { if (path in selected) selected.remove(path) else selected.add(path) },
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(path in selected, { if (it) selected.add(path) else selected.remove(path) })
                                if (dir.isNotEmpty()) {
                                    Text("$dir/", style = Mono.tiny, color = coder.muted, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                                }
                                Text(name, style = Mono.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Vazgeç") }
                    Button(onClick = { vm.saveContext(repo, ref, selected.toList()); onDismiss() }) { Text("Kaydet") }
                }
            }
        }
    }
}
