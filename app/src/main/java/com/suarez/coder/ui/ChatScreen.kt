package com.suarez.coder.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var showContext by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val version = vm.settingsVersion

    val results = vm.messages.filterIsInstance<ChatMessage.ToolResult>().associateBy { it.callId }
    val visible = vm.messages.filter { it !is ChatMessage.ToolResult && it !is ChatMessage.UsageRecord }
    val lastRoute = vm.messages.lastOrNull { it is ChatMessage.RouteInfo }

    val showScrollToBottom by remember {
        derivedStateOf {
            listState.canScrollForward
        }
    }

    LaunchedEffect(vm.messages.size, vm.running) {
        val count = visible.size + if (vm.running) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    fun shareChat() {
        val transcript = buildString {
            appendLine("# Coder Sohbet Geçmişi")
            appendLine("Tarih: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine("Maliyet: ${vm.sessionCostText}")
            appendLine()
            for (m in vm.messages) {
                when (m) {
                    is ChatMessage.User -> {
                        appendLine("### Kullanıcı")
                        appendLine(m.text)
                        appendLine()
                    }
                    is ChatMessage.Assistant -> {
                        appendLine("### Coder")
                        if (m.text.isNotBlank()) {
                            appendLine(m.text)
                            appendLine()
                        }
                        for (c in m.toolCalls) {
                            appendLine("- **Araç Çağrısı:** `${c.name}`")
                        }
                        appendLine()
                    }
                    is ChatMessage.Notice -> {
                        appendLine("> *${m.text}*")
                        appendLine()
                    }
                    else -> Unit
                }
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Coder Sohbeti")
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        context.startActivity(Intent.createChooser(intent, "Sohbeti Paylaş"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                title = {
                    Column {
                        Text("Coder", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                text = vm.sessionCostText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = ::shareChat, enabled = visible.isNotEmpty()) {
                        Icon(Icons.Default.Share, contentDescription = "Sohbeti Paylaş")
                    }
                    IconButton(onClick = { vm.newChat() }) {
                        Icon(Icons.Default.AddComment, contentDescription = "Yeni Sohbet")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ayarlar")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding(),
        ) {
            ControlBar(vm, onOpenContext = { showContext = true })

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (visible.isEmpty()) {
                        item {
                            EmptyState(
                                onPromptClick = { prompt ->
                                    input = prompt
                                    vm.onInputChanged(prompt)
                                },
                            )
                        }
                    }
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
                    if (vm.running) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = if (vm.pending != null) "Onay bekleniyor…" else "Çalışıyor…",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showScrollToBottom,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                val count = visible.size + if (vm.running) 1 else 0
                                if (count > 0) listState.animateScrollToItem(count - 1)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Aşağı kaydır")
                    }
                }
            }

            ComposerBar(
                input = input,
                preview = vm.preview,
                running = vm.running,
                onInputChange = {
                    input = it
                    vm.onInputChanged(it)
                },
                onSend = {
                    val prompt = input
                    input = ""
                    vm.send(prompt)
                },
                onStop = { vm.stop() },
                onOpenContext = { showContext = true },
                contextCount = vm.settings.contextFiles.size,
            )
        }
    }

    vm.pending?.let { p -> ConfirmDialog(p, onAnswer = { vm.answer(it) }) }
    if (showContext) ContextDialog(vm, onDismiss = { showContext = false })
}

@Composable
private fun ComposerBar(
    input: String,
    preview: String?,
    running: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenContext: () -> Unit,
    contextCount: Int,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            preview?.let {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = onOpenContext,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Bağlam Dosyaları",
                                tint = if (contextCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (contextCount > 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .align(Alignment.TopEnd),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = contextCount.toString(),
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp, max = 160.dp)
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                text = "Ne yapmamı istersin?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (running) {
                        FilledIconButton(
                            onClick = onStop,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Durdur")
                        }
                    } else {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = input.isNotBlank(),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlBar(vm: ChatViewModel, onOpenContext: () -> Unit) {
    val s = vm.settings
    val options = vm.modelOptions()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = s.architectMode,
                    onClick = { vm.setArchitectMode(!s.architectMode) },
                    label = { Text("Mimar Modu") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Architecture,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
                if (!s.architectMode) {
                    ModelChip("Model", vm.agent.singleRef(), options) { vm.selectSingle(it) }
                }
                FilterChip(
                    selected = s.smartRouting,
                    onClick = { vm.setSmartRouting(!s.smartRouting) },
                    label = { Text("Akıllı Yönlendirme") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
                ChoiceChip("Düşünme", s.thinkingLevel, listOf("low", "medium", "high")) { vm.setThinking(it) }
                AssistChip(
                    onClick = onOpenContext,
                    label = { Text("Bağlam: ${s.contextFiles.size}") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
            if (s.architectMode) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModelChip("Mimar", vm.agent.architectRef(), options) { vm.selectArchitect(it) }
                    ModelChip("Uygulayıcı", vm.agent.editorRef(), options) { vm.selectEditor(it) }
                }
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
private fun EmptyState(onPromptClick: (String) -> Unit) {
    val quickPrompts = listOf(
        "Repolarımı listele",
        "Son Actions derlemesi neden başarısız oldu?",
        "Mevcut reponun özetini çıkar",
        "Açık issue'ları listele",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Telefonundan kod yaz", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text(
            "Yapay zekâ destekli GitHub ve kod asistanı",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickPrompts.forEach { p ->
                ElevatedCard(
                    onClick = { onPromptClick(p) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = p,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(start = 48.dp),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun RouteBar(m: ChatMessage.RouteInfo, isLatest: Boolean, onOverride: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            Modifier
                .clickable(enabled = m.detail != null) { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = m.text,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (m.detail != null) {
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (open && m.detail != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = m.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (isLatest && m.canOverride) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(
                    onClick = onOverride,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("↻ Mimar Modu'nda yeniden çalıştır", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun NoticeItem(text: String, detail: String?, isError: Boolean) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = detail != null) { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = text,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (detail != null) {
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (open && detail != null) {
                Spacer(Modifier.height(6.dp))
                CodeBox(detail.take(30_000))
            }
        }
    }
}

@Composable
private fun AssistantItem(m: ChatMessage.Assistant, results: Map<String, ChatMessage.ToolResult>) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000L)
            copied = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Coder",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (m.text.isNotBlank()) {
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(m.text))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                        contentDescription = "Kopyala",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
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
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { open = !open },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        result == null -> Icons.Default.Terminal
                        result.isError -> Icons.Default.Error
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    tint = when {
                        result == null -> MaterialTheme.colorScheme.primary
                        result.isError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = call.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                summarize(call),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) 10 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (open) {
                Spacer(Modifier.height(8.dp))
                Text("Parametreler", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                CodeBox(call.args.toString(2).take(6000))
                if (result != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(if (result.isError) "Hata" else "Sonuç", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    CodeBox(result.content.take(8000))
                }
            }
        }
    }
}

@Composable
fun CodeBox(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            )
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
