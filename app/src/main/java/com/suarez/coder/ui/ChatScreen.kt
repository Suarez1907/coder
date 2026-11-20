package com.suarez.coder.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var showContext by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    @Suppress("UNUSED_VARIABLE") val version = vm.settingsVersion // recompose when settings change

    val results = vm.messages.filterIsInstance<ChatMessage.ToolResult>().associateBy { it.callId }
    val visible = vm.messages.filter { it !is ChatMessage.ToolResult && it !is ChatMessage.UsageRecord }
    val lastRoute = vm.messages.lastOrNull { it is ChatMessage.RouteInfo }

    LaunchedEffect(vm.messages.size, vm.running) {
        val count = visible.size + if (vm.running) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Coder")
                        Text(vm.sessionCostText, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "Yeni sohbet") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Ayarlar") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            ControlBar(vm, onOpenContext = { showContext = true })
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (visible.isEmpty()) item { EmptyState() }
                items(visible) { m ->
                    when (m) {
                        is ChatMessage.User -> UserBubble(m.text)
                        is ChatMessage.Assistant -> AssistantItem(m, results)
                        is ChatMessage.Notice -> NoticeItem(m.text, m.detail, m.isError)
                        is ChatMessage.Summary -> NoticeItem("🗜 Eski mesajlar özetlendi (token tasarrufu)", m.text, false)
                        is ChatMessage.RouteInfo -> RouteBar(m, isLatest = m === lastRoute) { vm.rerunAsArchitect() }
                        else -> Unit
                    }
                }
                if (vm.running) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (vm.pending != null) "Onay bekleniyor…" else "Çalışıyor…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            vm.preview?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 12.dp), maxLines = 2)
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; vm.onInputChanged(it) },
                    modifier = Modifier.weight(1f).heightIn(max = 180.dp),
                    placeholder = { Text("Ne yapmamı istersin?") },
                )
                Spacer(Modifier.width(8.dp))
                if (vm.running) {
                    FilledIconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Close, "Durdur") }
                } else {
                    FilledIconButton(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Gönder")
                    }
                }
            }
        }
    }

    vm.pending?.let { p -> ConfirmDialog(p, onAnswer = { vm.answer(it) }) }
    if (showContext) ContextDialog(vm, onDismiss = { showContext = false })
}

@Composable
private fun ControlBar(vm: ChatViewModel, onOpenContext: () -> Unit) {
    val s = vm.settings
    val options = vm.modelOptions()
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = s.architectMode,
                onClick = { vm.setArchitectMode(!s.architectMode) },
                label = { Text("Mimar Modu (Maliyet Tasarrufu)") },
            )
            if (!s.architectMode) {
                ModelChip("Model", vm.agent.singleRef(), options) { vm.selectSingle(it) }
            }
            FilterChip(
                selected = s.smartRouting,
                onClick = { vm.setSmartRouting(!s.smartRouting) },
                label = { Text("Akıllı Yönlendirme") },
            )
            ChoiceChip("Düşünme", s.thinkingLevel, listOf("low", "medium", "high")) { vm.setThinking(it) }
            AssistChip(onClick = onOpenContext, label = { Text("📎 Bağlam: ${s.contextFiles.size}") })
            AssistChip(onClick = { vm.clearCache() }, enabled = !vm.running, label = { Text("🧹 Önbelleği Temizle") })
        }
        if (s.architectMode) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModelChip("Mimar", vm.agent.architectRef(), options) { vm.selectArchitect(it) }
                ModelChip("Uygulayıcı", vm.agent.editorRef(), options) { vm.selectEditor(it) }
            }
        }
    }
}

@Composable
private fun ModelChip(label: String, current: ModelRef, options: List<ModelRef>, onPick: (ModelRef) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("$label: ${current.model} ▾", maxLines = 1) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { ref ->
                DropdownMenuItem(text = { Text(ref.label) }, onClick = { onPick(ref); open = false })
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, current: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("$label: $current ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("</>", fontSize = 40.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.size(12.dp))
        Text("Telefonundan kod yaz", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "Örnekler:\n• \"Repolarımı listele\"\n• \"dino reposuna skor tablosu ekle\"\n• \"Son Actions derlemesi neden başarısız oldu, düzelt\"\n\n" +
                "İpucu: Mimar Modu planı güçlü modele, kodlamayı ucuz modele yaptırır.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            SelectionContainer { Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun RouteBar(m: ChatMessage.RouteInfo, isLatest: Boolean, onOverride: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.clickable(enabled = m.detail != null) { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("⚡ " + m.text + if (m.detail != null) (if (open) "  ▴" else "  ▾") else "",
                style = MaterialTheme.typography.labelMedium)
            if (open && m.detail != null) {
                Text(m.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (isLatest && m.canOverride) {
                TextButton(onClick = onOverride, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text("↻ Mimar Modu'nda yeniden çalıştır", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun NoticeItem(text: String, detail: String?, isError: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize().clickable(enabled = detail != null) { open = !open }) {
        Text(
            text + if (detail != null) (if (open) "  ▴" else "  ▾") else "",
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (open && detail != null) CodeBox(detail.take(30_000))
    }
}

@Composable
private fun AssistantItem(m: ChatMessage.Assistant, results: Map<String, ChatMessage.ToolResult>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.text.isNotBlank()) MarkdownText(m.text)
        m.toolCalls.forEach { ToolCard(it, results[it.id]) }
    }
}

private fun summarize(call: ToolCall): String {
    val a = call.args
    return listOf("repo", "path", "branch", "query", "url", "method", "title", "commit_message")
        .mapNotNull { k -> a.optString(k).takeIf { a.has(k) && it.isNotBlank() } }
        .joinToString("  ")
        .ifBlank { a.toString().take(80) }
}

@Composable
private fun ToolCard(call: ToolCall, result: ChatMessage.ToolResult?) {
    var open by remember { mutableStateOf(false) }
    val status = when {
        result == null -> "⏳"
        result.isError -> "⚠️"
        else -> "✓"
    }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("$status ${call.name}", style = MaterialTheme.typography.labelLarge)
            Text(summarize(call), style = MaterialTheme.typography.bodySmall, maxLines = if (open) 10 else 1, overflow = TextOverflow.Ellipsis)
            if (open) {
                Spacer(Modifier.size(6.dp))
                Text("Parametreler", style = MaterialTheme.typography.labelSmall)
                CodeBox(call.args.toString(2).take(6000))
                if (result != null) {
                    Spacer(Modifier.size(6.dp))
                    Text(if (result.isError) "Hata" else "Sonuç", style = MaterialTheme.typography.labelSmall)
                    CodeBox(result.content.take(8000))
                }
            }
        }
    }
}

@Composable
fun CodeBox(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(8.dp))
        }
    }
}

@Composable
private fun ConfirmDialog(p: PendingConfirm, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(p.title) },
        text = { CodeBox(p.body) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(p.okText) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(p.cancelText) } },
    )
}
