package com.suarez.coder.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import com.suarez.coder.apk.ApkInstaller
import com.suarez.coder.apk.ApkLinks
import com.suarez.coder.media.ImagePrep
import java.io.File
import java.util.Locale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suarez.coder.cost.Pricing
import com.suarez.coder.llm.ChatMessage
import com.suarez.coder.llm.ModelRef
import com.suarez.coder.llm.ToolCall

// ---- Display model: consecutive tool calls are grouped into one collapsible card ----

private sealed class DItem {
    abstract val key: String

    data class Msg(val m: ChatMessage, override val key: String) : DItem()

    data class Tools(val calls: List<Pair<ToolCall, ChatMessage.ToolResult?>>, override val key: String) : DItem()
}

private fun buildItems(messages: List<ChatMessage>): List<DItem> {
    val results = messages.filterIsInstance<ChatMessage.ToolResult>().associateBy { it.callId }
    val out = ArrayList<DItem>()
    var group: MutableList<Pair<ToolCall, ChatMessage.ToolResult?>>? = null
    fun close() {
        group?.let { g -> if (g.isNotEmpty()) out += DItem.Tools(g.toList(), "g" + g.first().first.id) }
        group = null
    }
    messages.forEachIndexed { i, m ->
        when (m) {
            is ChatMessage.ToolResult, is ChatMessage.UsageRecord, ChatMessage.CompactMark -> Unit // hidden; do not break a group
            is ChatMessage.Assistant -> {
                if (m.text.isNotBlank()) {
                    close()
                    out += DItem.Msg(m.copy(toolCalls = emptyList()), "a$i")
                }
                if (m.toolCalls.isNotEmpty()) {
                    val g = group ?: ArrayList<Pair<ToolCall, ChatMessage.ToolResult?>>().also { group = it }
                    m.toolCalls.forEach { c -> g += c to results[c.id] }
                }
            }
            else -> {
                close()
                out += DItem.Msg(m, "m$i")
            }
        }
    }
    close()
    return out
}

// ---- Screen ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var showContext by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    var showDashboard by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    @Suppress("UNUSED_VARIABLE") val version = vm.settingsVersion // recompose when settings change

    val items = buildItems(vm.messages)
    val lastRoute = vm.messages.lastOrNull { it is ChatMessage.RouteInfo }
    val lastToolsKey = items.lastOrNull { it is DItem.Tools }?.key

    LaunchedEffect(vm.messages.size, vm.running) {
        val count = items.size + if (vm.running) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            HistoryDrawer(vm,
                onOpen = { id -> vm.openSession(id); scope.launch { drawer.close() } },
                onNewChat = { vm.newChat(); scope.launch { drawer.close() } },
                onDashboard = { showDashboard = true; scope.launch { drawer.close() } })
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ChatTopBar(vm, onOpenSettings, onMenu = { scope.launch { drawer.open() } }, onRepo = { showProfiles = true }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (items.isEmpty()) item { EmptyState() }
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is DItem.Tools -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToolGroupCard(item, active = vm.running && item.key == lastToolsKey)
                            val results = item.calls.mapNotNull { it.second?.content }.joinToString("\n")
                            ApkActions(vm, results)
                            CloudResultCards(vm, results)
                        }
                        is DItem.Msg -> when (val m = item.m) {
                            is ChatMessage.User -> UserBubble(com.suarez.coder.docs.DocumentReader.collapse(m.text), m.images)
                            is ChatMessage.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MarkdownText(m.text)
                                ApkActions(vm, m.text)
                            }
                            is ChatMessage.Notice -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val cp = com.suarez.coder.agent.Checkpoints.find(m.detail)
                                if (cp != null) CheckpointItem(vm, m.text, cp)
                                else {
                                    NoticeItem(m.text, m.detail, m.isError)
                                    m.detail?.let { CloudResultCards(vm, it) }
                                }
                            }
                            is ChatMessage.Summary -> NoticeItem("🗜 Eski mesajlar özetlendi", m.text, false)
                            is ChatMessage.RouteInfo -> RouteBar(m, isLatest = m === lastRoute) { vm.rerunAsArchitect() }
                            is ChatMessage.ContextBlock -> NoticeItem("📎 Bağlam: ${m.label}", m.text.take(20_000), false)
                            else -> Unit
                        }
                    }
                }
                if (!vm.running && vm.canRate && items.isNotEmpty()) item(key = "rate") { RateRow(vm) }
                vm.activePlan?.let { plan -> item(key = "plan-${plan.id}") { PlanCard(vm, plan) } }
                if (vm.running) item(key = "running") { RunningRow(vm) }
            }
            vm.preview?.let {
                Text(it, style = Mono.tiny, color = coder.muted, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
            }
            TodoCard(vm)
            ControlBar(vm) { showSheet = true }
            val context = LocalContext.current
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImagePrep.MAX_IMAGES)) { uris ->
                vm.addImages(uris)
            }
            // System speech recognizer: no API cost, no microphone permission needed by this app.
            val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                    input = (input.trimEnd() + " " + spoken).trim()
                    vm.onInputChanged(input)
                }
            }
            if (vm.attachments.isNotEmpty()) AttachmentRow(vm.attachments) { vm.removeImage(it) }
            val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addDocuments(uris) }
            if (vm.docs.isNotEmpty()) DocumentRow(vm)
            SlashSuggestions(input, vm.settings.promptTemplates + vm.skills.filter { it.enabled }.map { "/" + it.name to it.description }) { text ->
                input = if (text.startsWith("/")) text.substringBefore(' ') + " " else text
                vm.onInputChanged(input)
            }
            val atWord = Regex("@([^\\s@]*)$").find(input) != null
            LaunchedEffect(atWord) { if (atWord) vm.loadRepoPaths() }
            if (atWord) MentionSuggestions(com.suarez.coder.agent.Mentions.suggestions(input, vm.repoPaths)) { token ->
                input = input.replace(Regex("@([^\\s@]*)$"), token) + if (token.endsWith("://")) "" else " "
                vm.onInputChanged(input)
            }
            InputRow(input, running = vm.running, hasAttachments = vm.attachments.isNotEmpty() || vm.docs.any { it.text != null },
                enhancing = vm.enhancing,
                onEnhance = { vm.enhancePrompt(input) { better -> input = better; vm.onInputChanged(better) } },
                onDoc = {
                    if (vm.docs.size >= com.suarez.coder.docs.DocumentReader.MAX_DOCS) Toast.makeText(context, "En fazla ${com.suarez.coder.docs.DocumentReader.MAX_DOCS} belge", Toast.LENGTH_SHORT).show()
                    else docPicker.launch(arrayOf("*/*"))
                },
                onChange = { input = it; vm.onInputChanged(it) },
                onSend = { vm.send(input); if (vm.visionWarning == null) input = "" },
                onStop = { vm.stop() },
                onPick = {
                    if (vm.attachments.size >= ImagePrep.MAX_IMAGES) Toast.makeText(context, "En fazla ${ImagePrep.MAX_IMAGES} görsel", Toast.LENGTH_SHORT).show()
                    else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onMic = {
                    val lang = if (Locale.getDefault().language == "tr") "tr-TR" else Locale.getDefault().toLanguageTag()
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Görevini söyle")
                    try { speech.launch(intent) } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "Bu cihazda ses tanıma bulunamadı", Toast.LENGTH_SHORT).show()
                    }
                })
        }
    }
    }

    if (showProfiles) {
        ModalBottomSheet(
            onDismissRequest = { showProfiles = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ProfileSheet(vm, onDone = { showProfiles = false })
        }
    }
    if (showDashboard) CostDashboard(vm, onDismiss = { showDashboard = false })
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ControlSheet(vm, onOpenContext = { showSheet = false; showContext = true })
        }
    }
    // Android 13+: notification permission for the live CI notification, asked once when the first CI wait starts.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(vm.askNotificationPermission) {
        if (vm.askNotificationPermission) {
            vm.notificationPermissionAsked()
            if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    vm.pending?.let { p -> ConfirmDialog(p, onAnswer = { vm.answer(it) }) }
    vm.visionWarning?.let { w ->
        AlertDialog(
            onDismissRequest = { vm.answerVisionWarning(false) },
            title = { Text("Model görselleri göremiyor") },
            text = { Text(w) },
            confirmButton = { TextButton(onClick = { vm.answerVisionWarning(true); input = "" }) { Text("Yine de gönder") } },
            dismissButton = { TextButton(onClick = { vm.answerVisionWarning(false) }) { Text("Vazgeç") } },
        )
    }
    if (showContext) ContextDialog(vm, onDismiss = { showContext = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(vm: ChatViewModel, onOpenSettings: () -> Unit, onMenu: () -> Unit = {}, onRepo: () -> Unit = {}) {
    val s = vm.settings
    val repo = s.contextRepo.ifBlank { s.defaultRepo }.substringAfterLast('/').ifBlank { null }
    val cost = vm.agent.sessionCost(vm.messages)
    val calls = vm.messages.filterIsInstance<ChatMessage.UsageRecord>().filter { it.role != "cache" && it.role != "decision" }
    val hit = vm.agent.cacheHitRate(calls)
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Sohbetler") } },
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Coder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRepo)) {
                        Badge((repo ?: "repo yok") + " ▾", if (repo != null) coder.editor else coder.muted)
                    }
                }
                Row {
                    Text(Pricing.formatUsd(cost), style = Mono.small, color = coder.cost)
                    Text(" · ${Pricing.formatTry(cost * s.usdTry)}", style = Mono.small, color = coder.muted)
                    if (hit != null) {
                        val pct = (hit * 100).toInt()
                        Text(" · Önbellek ", style = Mono.small, color = coder.muted)
                        Text("%$pct", style = Mono.small, color = cacheColor(hit))
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "Yeni sohbet") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Ayarlar") }
        },
    )
}

@Composable
private fun cacheColor(rate: Double): Color = when {
    rate >= 0.6 -> coder.cacheGood
    rate >= 0.3 -> coder.cacheMid
    else -> coder.muted
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text,
        style = Mono.tiny,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ---- Bottom: compact control bar + input ----

private fun thinkingShort(level: String) = when (level) {
    "minimal" -> "Min"; "low" -> "Low"; "medium" -> "Med"; "high" -> "High"; else -> level
}

@Composable
private fun ControlBar(vm: ChatViewModel, onOpen: () -> Unit) {
    val s = vm.settings
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .border(1.dp, coder.border, RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (s.architectMode) {
                Text("Mimar: ", style = Mono.small, color = coder.muted)
                Text(shortModel(vm.agent.architectRef().model), style = Mono.small, color = coder.architect)
                Text(" · Uyg: ", style = Mono.small, color = coder.muted)
                Text(shortModel(vm.agent.editorRef().model), style = Mono.small, color = coder.editor)
            } else {
                Text("Model: ", style = Mono.small, color = coder.muted)
                Text(shortModel(vm.agent.singleRef().model), style = Mono.small, color = coder.architect)
            }
            Text(" · ${thinkingShort(s.thinkingLevel)} · Bağlam: ${s.contextFiles.size}", style = Mono.small, color = coder.muted)
            if (s.smartRouting) Text(" · ⚡", style = Mono.small)
            if (s.economyMode) Text(" · Flex", style = Mono.small, color = coder.cost)
            val orch = com.suarez.coder.orchestrator.OrchestratorMode.of(s.orchestratorMode)
            if (orch != com.suarez.coder.orchestrator.OrchestratorMode.OFF) Text(" · Ork: ${orch.short}", style = Mono.small, color = coder.architect)
            vm.agent.activeMode()?.let { Text(" · Mod: ${it.name}", style = Mono.small, color = coder.editor) }
            if (vm.backgroundRuns.isNotEmpty()) Text(" · Arka plan: ${vm.backgroundRuns.size}", style = Mono.small, color = coder.muted)
        }
        Icon(Icons.Default.KeyboardArrowUp, "Kontroller", tint = coder.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InputRow(
    text: String,
    running: Boolean,
    hasAttachments: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPick: () -> Unit,
    onMic: () -> Unit,
    onDoc: () -> Unit = {},
    enhancing: Boolean = false,
    onEnhance: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 2.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).heightIn(max = 180.dp),
            placeholder = { Text("Ne yapmamı istersin?") },
            // Thumb-reachable extras inside the field: screenshots (left) and dictation (right).
            leadingIcon = {
                Row {
                    IconButton(onClick = onPick, enabled = !running) { Text("🖼", fontSize = 18.sp) }
                    IconButton(onClick = onDoc, enabled = !running) { Icon(Icons.Outlined.Add, "Belge ekle", tint = coder.muted) }
                }
            },
            trailingIcon = {
                Row {
                    if (text.isNotBlank()) IconButton(onClick = onEnhance, enabled = !running && !enhancing) {
                        if (enhancing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Edit, "İsteği iyileştir", tint = coder.muted)
                    }
                    IconButton(onClick = onMic, enabled = !running) { Text("🎙", fontSize = 18.sp) }
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = coder.border,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Spacer(Modifier.width(8.dp))
        if (running) {
            FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Close, "Durdur") }
        } else {
            FilledIconButton(onClick = onSend, enabled = text.isNotBlank() || hasAttachments, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Gönder")
            }
        }
    }
}

/** Template list shown while the input is "/" or "/name…" (one word, no spaces yet). */
@Composable
private fun SlashSuggestions(input: String, templates: List<Pair<String, String>>, onPick: (String) -> Unit) {
    if (!input.startsWith("/") || input.contains(' ') || input.contains('\n')) return
    val query = input.removePrefix("/").lowercase()
    // Skills are listed as "/name" → description; picking one inserts "/name " for the user to complete.
    val matches = templates.map { (n, t) -> if (n.startsWith("/")) n.removePrefix("/") to "$n $t" else n to t }
        .filter { it.first.lowercase().startsWith(query) }.distinctBy { it.first }
    if (matches.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, coder.border),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            matches.forEach { (name, text) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(text) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("/$name", style = Mono.small, color = coder.architect, modifier = Modifier.width(96.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = coder.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Small, memory-friendly preview of a prepared image file. */
@Composable
private fun Thumb(path: String, size: Int, modifier: Modifier = Modifier) {
    val bmp = remember(path) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(bmp, null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, coder.border, RoundedCornerShape(8.dp)))
    } else {
        Box(modifier.size(size.dp).border(1.dp, coder.border, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text("🖼", color = coder.muted)
        }
    }
}

@Composable
private fun AttachmentRow(paths: List<String>, onRemove: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEach { p ->
            Box {
                Thumb(p, 56)
                Text(
                    "×",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .clickable { onRemove(p) }
                        .padding(horizontal = 6.dp),
                )
            }
        }
        Text("${paths.size}/${ImagePrep.MAX_IMAGES}", style = Mono.tiny, color = coder.muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

/** "Buraya geri dön": the branch head saved before the agent's first write in that run. */
@Composable
private fun CheckpointItem(vm: ChatViewModel, text: String, p: com.suarez.coder.agent.Checkpoints.Point) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Mono.tiny, color = coder.muted, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        TextButton(onClick = { vm.rewind(p) }, enabled = !vm.running) { Text("Buraya geri dön", style = Mono.tiny) }
    }
}

/** The agent's task list (update_todos) for the current request. */
@Composable
private fun TodoCard(vm: ChatViewModel) {
    val items = remember(vm.messages.size) { com.suarez.coder.agent.Todos.latest(vm.messages) }
    if (items.isEmpty()) return
    val done = items.count { it.status == com.suarez.coder.agent.Todos.Status.DONE }
    if (done == items.size && !vm.running) return
    var open by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
            .border(1.dp, coder.border, RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text("Görevler $done/${items.size}", style = Mono.small, color = coder.muted)
        if (open) items.forEach { i ->
            val (mark, color) = when (i.status) {
                com.suarez.coder.agent.Todos.Status.DONE -> "[x]" to coder.muted
                com.suarez.coder.agent.Todos.Status.IN_PROGRESS -> "[>]" to MaterialTheme.colorScheme.onSurface
                com.suarez.coder.agent.Todos.Status.PENDING -> "[ ]" to coder.muted
            }
            Text("$mark ${i.text}", style = Mono.small, color = color, maxLines = 2)
        }
    }
}

@Composable
private fun MentionSuggestions(items: List<String>, onPick: (String) -> Unit) {
    if (items.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { t ->
            Text(t, style = Mono.small, maxLines = 1,
                modifier = Modifier.border(1.dp, coder.border, RoundedCornerShape(8.dp)).clickable { onPick(t) }.padding(horizontal = 8.dp, vertical = 5.dp))
        }
    }
}

/** Documents waiting to be sent: name, state, and remove. */
@Composable
private fun DocumentRow(vm: ChatViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        vm.docs.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = Mono.small, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(d.busy?.let { "$it…" } ?: d.error ?: "${d.text?.length ?: 0} karakter okundu",
                        style = Mono.tiny, color = if (d.error != null) MaterialTheme.colorScheme.error else coder.muted, maxLines = 3)
                }
                TextButton(onClick = { vm.removeDocument(d.id) }) { Text("Kaldır") }
            }
        }
        vm.docStatus?.let { Text(it, style = Mono.tiny, color = coder.muted) }
    }
}

/** "Download & install" buttons for APK links found in [text]. */
@Composable
internal fun ApkActions(vm: ChatViewModel, text: String) {
    if (!text.contains("http")) return
    val links = remember(text) { ApkLinks.find(text) }
    if (links.isEmpty()) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        links.forEach { (url, target) ->
            val state = vm.downloads[url]
            val label = when (state) {
                null -> "⬇ İndir ve Kur"
                is DownloadState.Running -> "İndiriliyor %${state.percent}"
                is DownloadState.Ready -> "📦 Kur"
                is DownloadState.Failed -> "↻ Tekrar dene"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        when (state) {
                            is DownloadState.Ready -> {
                                val started = runCatching { ApkInstaller.install(context, File(state.path)) }.getOrElse { false }
                                if (!started) Toast.makeText(context, "Coder'a uygulama yükleme izni verin, sonra tekrar dokunun", Toast.LENGTH_LONG).show()
                            }
                            is DownloadState.Running -> Unit
                            else -> vm.downloadApk(url, target)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, coder.editor.copy(alpha = 0.6f)),
                ) {
                    if (state is DownloadState.Running) {
                        CircularProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = coder.editor)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label, style = Mono.small, color = coder.editor)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    (state as? DownloadState.Failed)?.message ?: url.substringAfter("github.com/").take(48),
                    style = Mono.tiny, color = if (state is DownloadState.Failed) coder.warn else coder.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- Bottom sheet: every control within thumb reach ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlSheet(vm: ChatViewModel, onOpenContext: () -> Unit) {
    val s = vm.settings
    @Suppress("UNUSED_VARIABLE") val version = vm.settingsVersion
    val options = vm.modelOptions()
    val priceOf: (ModelRef) -> String = { vm.priceLabel(it) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Çalışma ayarları", style = MaterialTheme.typography.titleMedium)
        val customList = vm.customModes()
        if (customList.isNotEmpty()) {
            Text("Mod", style = MaterialTheme.typography.labelLarge, color = coder.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("" to "Otomatik") + customList.map { it.name to it.name }).forEach { (key, label) ->
                    val on = s.activeMode == key
                    OutlinedButton(onClick = { vm.setActiveMode(key) },
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.onSurface else coder.border)) {
                        Text(label, style = Mono.small, color = if (on) MaterialTheme.colorScheme.onSurface else coder.muted)
                    }
                }
            }
        }
        SheetSwitch("Mimar Modu", "Planı güçlü model, kodu ucuz model yazar", s.architectMode) { vm.setArchitectMode(it) }
        SheetSwitch("Akıllı Yönlendirme", "Yerel kural → Decision 1 → ucuz model", s.smartRouting) { vm.setSmartRouting(it) }
        SheetSwitch("Ekonomi (Flex %50)", "Yarı fiyat; yanıtlar dakikalar sürebilir", s.economyMode) { vm.setEconomy(it) }

        HorizontalDivider(color = coder.border)
        if (s.architectMode) {
            ModelSelect("Mimar", vm.agent.architectRef(), coder.architect, options, priceOf) { vm.selectArchitect(it) }
            ModelSelect("Uygulayıcı", vm.agent.editorRef(), coder.editor, options, priceOf) { vm.selectEditor(it) }
        } else {
            ModelSelect("Model", vm.agent.singleRef(), coder.architect, options, priceOf) { vm.selectSingle(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.refreshCatalog() }) { Text("↻ Model listesini güncelle") }
            vm.catalogStatus?.let { Text(it, style = Mono.tiny, color = coder.muted) }
        }

        Text("Orkestratör", style = MaterialTheme.typography.labelLarge, color = coder.muted)
        val modes = com.suarez.coder.orchestrator.OrchestratorMode.entries
        val orch = com.suarez.coder.orchestrator.OrchestratorMode.of(s.orchestratorMode)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = orch == m,
                    onClick = { vm.setOrchestratorMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        activeBorderColor = coder.border, inactiveBorderColor = coder.border,
                    ),
                ) { Text("Ork: " + m.short) }
            }
        }
        Text(when (orch) {
            com.suarez.coder.orchestrator.OrchestratorMode.OFF -> "Normal akış; ek çağrı yok."
            com.suarez.coder.orchestrator.OrchestratorMode.LIGHT_SEQUENTIAL -> "Mimar'a giden işler alt görevlere bölünür, sırayla ve ayrı commit'lerle yapılır."
            com.suarez.coder.orchestrator.OrchestratorMode.FULL_MULTI_AGENT -> "⚠️ Tam mod daha pahalıdır: Planlayıcı + paralel işçiler + İnceleyici ayrı çağrılar yapar."
        }, style = MaterialTheme.typography.bodySmall,
            color = if (orch == com.suarez.coder.orchestrator.OrchestratorMode.FULL_MULTI_AGENT) coder.cost else coder.muted)

        Text("Düşünme", style = MaterialTheme.typography.labelLarge, color = coder.muted)
        val levels = listOf("low", "medium", "high")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            levels.forEachIndexed { i, lv ->
                SegmentedButton(
                    selected = s.thinkingLevel == lv,
                    onClick = { vm.setThinking(lv) },
                    shape = SegmentedButtonDefaults.itemShape(i, levels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        activeBorderColor = coder.border, inactiveBorderColor = coder.border,
                    ),
                ) { Text(lv) }
            }
        }

        HorizontalDivider(color = coder.border)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bağlam dosyaları", style = MaterialTheme.typography.bodyLarge)
                Text(if (s.contextFiles.isEmpty()) "Seçili dosya yok" else "${s.contextFiles.size} dosya sabitlendi",
                    style = MaterialTheme.typography.bodySmall, color = coder.muted)
            }
            OutlinedButton(onClick = onOpenContext) { Text("📎 Düzenle") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Önbellek", style = MaterialTheme.typography.bodyLarge)
                Text(vm.agent.cacheStatus, style = Mono.tiny, color = coder.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { vm.clearCache() }, enabled = !vm.running) { Text("🧹 Temizle") }
        }
    }
}

@Composable
private fun SheetSwitch(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = coder.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(value, onChange)
    }
}

@Composable
internal fun ModelSelect(label: String, current: ModelRef, accent: Color, options: List<ModelRef>, priceOf: (ModelRef) -> String, onPick: (ModelRef) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, coder.border, RoundedCornerShape(10.dp))
            .clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = coder.muted, modifier = Modifier.width(92.dp))
        Text(current.model, style = Mono.body, color = accent, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(current.provider.label, style = Mono.tiny, color = coder.muted)
        Text(" ▾", color = coder.muted)
    }
    if (open) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("$label modeli") },
            text = {
                Column {
                    if (options.size > 12) {
                        OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Ara: claude, flash, openrouter…") },
                            textStyle = Mono.small, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.size(8.dp))
                    }
                    val shown = options.filter { q.isBlank() || it.key.contains(q.trim(), ignoreCase = true) || it.provider.label.contains(q.trim(), true) }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.key }) { ref ->
                            val selected = ref.key == current.key
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { onPick(ref); open = false }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                            ) {
                                Text(ref.model, style = Mono.small, color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${ref.provider.label} · ${priceOf(ref)}", style = Mono.tiny, color = coder.muted)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Kapat") } },
        )
    }
}

// ---- Message items ----

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("</>", fontSize = 40.sp, color = coder.architect, style = Mono.body.copy(fontSize = 40.sp))
        Spacer(Modifier.size(12.dp))
        Text("Telefonundan kod yaz", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(10.dp))
        Text(
            "• \"Repolarımı listele\"\n• \"dino reposuna skor tablosu ekle\"\n• \"Son derleme neden başarısız oldu, düzelt\"\n\n" +
                "Modeller ve modlar alttaki çubuktan.",
            style = MaterialTheme.typography.bodySmall,
            color = coder.muted,
        )
    }
}

@Composable
private fun UserBubble(text: String, images: List<String> = emptyList()) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                images.forEach { Thumb(it, 72) }
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(start = 40.dp),
        ) {
            SelectionContainer { Text(text, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun RunningRow(vm: ChatViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = coder.architect)
        Spacer(Modifier.width(8.dp))
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(vm.status) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
        val secs = ((now - vm.statusSince) / 1000).coerceAtLeast(0)
        Text(
            when {
                vm.pending != null -> "Onay bekleniyor…"
                vm.status != null -> "${vm.status} · ${secs} sn"
                else -> "Çalışıyor…"
            },
            style = Mono.tiny,
            color = coder.muted,
        )
    }
}

@Composable
private fun RouteBar(m: ChatMessage.RouteInfo, isLatest: Boolean, onOverride: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, coder.architect.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .background(coder.architect.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .clickable(enabled = m.detail != null) { open = !open }
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .animateContentSize(),
    ) {
        Text("⚡ " + m.text + if (m.detail != null) (if (open) "  ▴" else "  ▾") else "", style = Mono.small, color = MaterialTheme.colorScheme.onSurface)
        if (open && m.detail != null) {
            Spacer(Modifier.size(4.dp))
            Text(m.detail, style = Mono.tiny, color = coder.muted)
        }
        if (isLatest && m.canOverride) {
            TextButton(onClick = onOverride, contentPadding = PaddingValues(0.dp)) {
                Text("↻ Mimar Modu'nda yeniden çalıştır", style = MaterialTheme.typography.labelMedium, color = coder.architect)
            }
        }
    }
}

@Composable
private fun NoticeItem(text: String, detail: String?, isError: Boolean) {
    var open by remember { mutableStateOf(false) }
    val isCost = text.startsWith("💰")
    val diff = rememberDiffFiles(detail)
    var showDiff by remember { mutableStateOf(false) }
    if (showDiff && diff != null) DiffViewer(diff) { showDiff = false }
    Column(Modifier.fillMaxWidth().animateContentSize().clickable(enabled = detail != null) { open = !open }) {
        Text(
            text + if (detail != null) (if (open) "  ▴" else "  ▾") else "",
            style = if (isCost) Mono.tiny else MaterialTheme.typography.bodySmall,
            color = when {
                isError -> coder.warn
                isCost -> coder.cost
                else -> coder.muted
            },
        )
        if (diff != null) {
            TextButton(onClick = { showDiff = true }, contentPadding = PaddingValues(0.dp)) {
                Text("🔍 Diff'i görüntüle", style = MaterialTheme.typography.labelMedium, color = coder.editor)
            }
        }
        if (open && detail != null) {
            Spacer(Modifier.size(4.dp))
            CodeBox(detail.take(30_000))
        }
    }
}

// ---- Collapsible tool-call group ----

private fun summarize(call: ToolCall): String {
    val a = call.args
    return listOf("path", "query", "url", "method", "title", "branch", "commit_message", "workflow")
        .mapNotNull { k -> a.optString(k).takeIf { a.has(k) && it.isNotBlank() } }
        .joinToString("  ")
        .ifBlank {
            a.optJSONArray("paths")?.let { p -> (0 until p.length()).joinToString(", ") { p.optString(it) } } ?: ""
        }
}

@Composable
private fun ToolGroupCard(item: DItem.Tools, active: Boolean) {
    val total = item.calls.size
    val errors = item.calls.count { it.second?.isError == true }
    val pending = item.calls.firstOrNull { it.second == null }
    val inProgress = active && pending != null
    var userOpen by remember(item.key) { mutableStateOf<Boolean?>(null) }
    // Open while running, fold into a one-line summary when done (unless the user toggled it).
    val expanded = userOpen ?: inProgress
    val repo = item.calls.firstNotNullOfOrNull { it.first.args.optString("repo").takeIf { r -> r.isNotBlank() } }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, coder.border),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { userOpen = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    inProgress -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = coder.editor)
                    errors > 0 -> Text("⚠", color = coder.warn)
                    pending != null -> Text("■", color = coder.muted)
                    else -> Text("✓", color = coder.cacheGood)
                }
                Spacer(Modifier.width(8.dp))
                val headline = when {
                    inProgress -> "${pending!!.first.name} çalışıyor (${total - item.calls.count { it.second == null } + 1}/$total)"
                    pending != null -> "${item.calls.count { it.second != null }}/$total işlem · durduruldu"
                    else -> "$total işlem tamamlandı" + if (errors > 0) " · $errors hata" else ""
                }
                Text(headline + (repo?.let { " · $it" } ?: ""), style = Mono.small, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (errors > 0 && !inProgress) coder.warn else MaterialTheme.colorScheme.onSurface)
                Text(if (expanded) "▴" else "▾", color = coder.muted)
            }
            if (expanded) {
                HorizontalDivider(color = coder.border)
                Column(Modifier.padding(vertical = 4.dp)) {
                    item.calls.forEach { (call, result) -> ToolRow(call, result, isActive = inProgress && call === pending?.first) }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(call: ToolCall, result: ChatMessage.ToolResult?, isActive: Boolean) {
    var open by remember(call.id) { mutableStateOf(false) }
    val diff = remember(call.id) { diffFilesFor(call.name, call.args) }
    var showDiff by remember(call.id) { mutableStateOf(false) }
    if (showDiff && diff != null) DiffViewer(diff) { showDiff = false }
    Column(Modifier.fillMaxWidth().clickable { if (diff != null) showDiff = true else open = !open }.padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                isActive -> CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = coder.editor)
                result == null -> Text("·", style = Mono.small, color = coder.muted)
                result.isError -> Text("⚠", style = Mono.small, color = coder.warn)
                else -> Text("✓", style = Mono.small, color = coder.cacheGood)
            }
            Spacer(Modifier.width(8.dp))
            Text(call.name, style = Mono.small, color = coder.editor)
            Spacer(Modifier.width(8.dp))
            Text(summarize(call), style = Mono.tiny, color = coder.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (diff != null) Text(" 🔍", style = Mono.tiny)
        }
        if (diff != null && result?.isError == true) Text(result.content.take(300), style = Mono.tiny, color = coder.warn)
        if (open) {
            Spacer(Modifier.size(4.dp))
            Text("Parametreler", style = MaterialTheme.typography.labelSmall, color = coder.muted)
            CodeBox(call.args.toString(2).take(6000))
            if (result != null) {
                Spacer(Modifier.size(4.dp))
                Text(if (result.isError) "Hata" else "Sonuç", style = MaterialTheme.typography.labelSmall,
                    color = if (result.isError) coder.warn else coder.muted)
                CodeBox(result.content.take(8000))
            }
        }
    }
}

@Composable
fun CodeBox(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = coder.codeBg,
        border = BorderStroke(1.dp, coder.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(text, style = Mono.tiny,
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
