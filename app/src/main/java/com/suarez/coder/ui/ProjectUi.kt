package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.suarez.coder.cost.CostBreakdown
import com.suarez.coder.cost.Pricing
import com.suarez.coder.data.ProjectProfile
import com.suarez.coder.data.SessionMeta
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- History drawer ----

@Composable
fun HistoryDrawer(vm: ChatViewModel, onOpen: (String) -> Unit, onNewChat: () -> Unit, onDashboard: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SessionMeta>?>(null) }
    var confirmDelete by remember { mutableStateOf<SessionMeta?>(null) }
    LaunchedEffect(query, vm.sessions) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(250)
        results = vm.searchSessions(query)
    }
    val list = results ?: vm.sessions
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale("tr")) }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        SystemBarsEffect()
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp).navigationBarsPadding()) {
            Spacer(Modifier.height(16.dp))
            Text("Sohbetler", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Ara: başlık, repo, içerik") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp)) },
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = coder.border),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Yeni sohbet")
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (list.isEmpty()) item {
                    Text(if (query.isBlank()) "Henüz kayıtlı sohbet yok." else "Sonuç yok.", style = MaterialTheme.typography.bodySmall,
                        color = coder.muted, modifier = Modifier.padding(8.dp))
                }
                items(list, key = { it.id }) { s ->
                    val active = s.id == vm.currentSessionId
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                            .clickable { onOpen(s.id) }
                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(s.repo.substringAfterLast('/').ifBlank { null }, fmt.format(Date(s.updatedAt)), Pricing.formatUsd(s.costUsd))
                                    .joinToString(" · "),
                                style = Mono.tiny, color = coder.muted, maxLines = 1,
                            )
                        }
                        IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Outlined.Delete, "Sil", tint = coder.muted, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            HorizontalDivider(color = coder.border)
            TextButton(onClick = onDashboard, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Icon(Icons.Outlined.Analytics, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Maliyet Panosu")
            }
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Sohbet silinsin mi?") },
            text = { Text("\"${s.title}\" ve ekran görüntüleri silinir. Harcaması maliyet panosunda kalır.") },
            confirmButton = { TextButton(onClick = { vm.deleteSession(s.id); confirmDelete = null }) { Text("Sil", color = coder.warn) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Vazgeç") } },
        )
    }
}

// ---- Project profiles ----

@Composable
fun ProfileSheet(vm: ChatViewModel, onDone: () -> Unit) {
    SystemBarsEffect()
    var editing by remember { mutableStateOf<ProjectProfile?>(null) }
    val activeId = vm.settings.activeProfileId
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Projeler", style = MaterialTheme.typography.titleMedium)
        vm.profiles.forEach { p ->
            val active = p.id == activeId
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (active) coder.architect.copy(alpha = 0.6f) else coder.border),
                modifier = Modifier.fillMaxWidth().clickable { vm.activateProfile(p.id); onDone() },
            ) {
                Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = if (active) coder.architect else coder.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.repo.ifBlank { "(repo yok)" }, style = Mono.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                p.branch.ifBlank { null },
                                p.architectModel.ifBlank { null }?.let { "Mimar ${shortModel(it.substringAfter('/'))}" },
                                p.editorModel.ifBlank { null }?.let { "Uyg ${shortModel(it.substringAfter('/'))}" },
                                p.contextFiles.size.takeIf { it > 0 }?.let { "$it dosya" },
                                p.instructions.ifBlank { null }?.let { "talimat" },
                            ).joinToString(" · ").ifBlank { "varsayılan ayarlar" },
                            style = Mono.tiny, color = coder.muted, maxLines = 1,
                        )
                    }
                    TextButton(onClick = { editing = p }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Düzenle", tint = coder.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        OutlinedButton(onClick = { editing = ProjectProfile() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Yeni profil")
        }
    }
    editing?.let { p ->
        ProfileEditor(p, canDelete = vm.profiles.size > 1 && vm.profiles.any { it.id == p.id },
            onSave = { vm.saveProfile(it); editing = null },
            onDelete = { vm.deleteProfile(p.id); editing = null },
            onDismiss = { editing = null })
    }
}

@Composable
private fun ProfileEditor(p: ProjectProfile, canDelete: Boolean, onSave: (ProjectProfile) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var repo by remember { mutableStateOf(p.repo) }
    var branch by remember { mutableStateOf(p.branch) }
    var instr by remember { mutableStateOf(p.instructions) }
    var arch by remember { mutableStateOf(p.architectModel) }
    var editor by remember { mutableStateOf(p.editorModel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.repo.isBlank()) "Yeni profil" else "Profili düzenle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(repo, { repo = it }, label = { Text("Repo (sahip/isim)") }, singleLine = true, textStyle = Mono.body)
                OutlinedTextField(branch, { branch = it }, label = { Text("Varsayılan branch (isteğe bağlı)") }, singleLine = true, textStyle = Mono.body)
                OutlinedTextField(instr, { instr = it }, label = { Text("Proje talimatları") }, minLines = 3)
                OutlinedTextField(arch, { arch = it }, label = { Text("Mimar modeli (boş = genel ayar)") }, singleLine = true, textStyle = Mono.small,
                    placeholder = { Text("gemini/gemini-4-hydrogen", style = Mono.small) })
                OutlinedTextField(editor, { editor = it }, label = { Text("Uygulayıcı modeli (boş = genel ayar)") }, singleLine = true, textStyle = Mono.small,
                    placeholder = { Text("gemini/gemini-3.8-flash", style = Mono.small) })
                if (canDelete) TextButton(onClick = onDelete) { Text("Profili sil", color = coder.warn) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(p.copy(repo = repo.trim().removePrefix("https://github.com/").trim('/'), branch = branch.trim(),
                    instructions = instr, architectModel = arch.trim(), editorModel = editor.trim()))
            }, enabled = repo.isNotBlank()) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

// ---- Cost dashboard ----

@Composable
fun CostDashboard(vm: ChatViewModel, onDismiss: () -> Unit) {
    var data by remember { mutableStateOf<CostBreakdown?>(null) }
    var finops by remember { mutableStateOf<com.suarez.coder.cost.FinOpsReport.Summary?>(null) }
    LaunchedEffect(Unit) {
        data = vm.costBreakdown()
        finops = vm.finOpsSummary()
    }
    val rate = vm.settings.usdTry
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SystemBarsEffect()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Kapat") }
                    Text("Maliyet Panosu", style = MaterialTheme.typography.titleLarge)
                }
                val d = data
                if (d == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Hesaplanıyor…", color = coder.muted) }
                    return@Column
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard("Toplam", d.totalUsd, rate, Modifier.weight(1f))
                        StatCard("Bu ay", d.monthUsd, rate, Modifier.weight(1f))
                    }
                    finops?.let { FinOpsCard(it) }
                    DashGroup("Önbellek isabeti") {
                        Text(d.cacheHitRate?.let { "%${(it * 100).toInt()}" } ?: "–", style = Mono.body.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize, fontFeatureSettings = "tnum"),
                            color = d.cacheHitRate?.let { if (it >= 0.6) coder.cacheGood else if (it >= 0.3) coder.cacheMid else coder.muted } ?: coder.muted)
                        Text("Önbellekten okunan giriş tokenı oranı (karar ve depolama hariç).", style = MaterialTheme.typography.bodySmall, color = coder.muted)
                    }
                    DashGroup("Repo bazında") {
                        if (d.byRepo.isEmpty()) Text("Kayıt yok.", color = coder.muted)
                        d.byRepo.forEach { (repo, usd, hit) ->
                            BarRow(repo, usd, d.totalUsd, rate, hit?.let { "önbellek %${(it * 100).toInt()}" })
                        }
                    }
                    DashGroup("Rol dağılımı") {
                        d.byRole.forEach { (role, usd) -> BarRow(role, usd, d.totalUsd, rate, null) }
                    }
                    DashGroup("Aylar") {
                        d.byMonth.forEach { (m, usd) -> BarRow(m, usd, d.byMonth.maxOf { it.second }, rate, null) }
                    }
                    Text("Tutarlar kayıt anındaki fiyatlarla hesaplanır; TL karşılığı Ayarlar'daki kurdan (1 USD = ₺$rate).",
                        style = MaterialTheme.typography.bodySmall, color = coder.muted)
                }
            }
        }
    }
}

@Composable
private fun FinOpsCard(s: com.suarez.coder.cost.FinOpsReport.Summary) {
    DashGroup("FinOps Tasarrufları") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Analytics, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
            Text("Turn-0 Paketleyici", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${s.turn0Runs} görev · ${s.turn0SavedTurns} tur · ${Pricing.formatTokens(s.turn0SavedTokens)}",
                style = Mono.small.copy(fontFeatureSettings = "tnum"), color = coder.muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.PauseCircle, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
            Text("Akış Devre Kesicisi", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${s.watchdogCuts} kesinti · ${Pricing.formatUsd(s.watchdogSavedUsd)}",
                style = Mono.small.copy(fontFeatureSettings = "tnum"), color = coder.cost)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
            Text("Sıfır-Token Code-Mod", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${s.codeModFiles} dosya", style = Mono.small.copy(fontFeatureSettings = "tnum"), color = coder.muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Layers, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
            Text("Flex/Önbellek", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(Pricing.formatUsd(s.flexSavedUsd), style = Mono.small.copy(fontFeatureSettings = "tnum"), color = coder.cost)
        }
    }
}

@Composable
private fun StatCard(title: String, usd: Double, rate: Double, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, coder.border, shape).padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = coder.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Pricing.formatUsd(usd), style = Mono.body.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize, fontFeatureSettings = "tnum"), color = coder.cost, maxLines = 1)
        Text(Pricing.formatTry(usd * rate), style = Mono.small.copy(fontFeatureSettings = "tnum"), color = coder.muted, maxLines = 1)
    }
}

@Composable
private fun DashGroup(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, coder.border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun BarRow(label: String, usd: Double, max: Double, rate: Double, extra: String?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Mono.small.copy(fontFeatureSettings = "tnum"), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Pricing.formatUsd(usd), style = Mono.small.copy(fontFeatureSettings = "tnum"), color = coder.cost)
            Text(" · " + Pricing.formatTry(usd * rate), style = Mono.tiny.copy(fontFeatureSettings = "tnum"), color = coder.muted)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.06f))) {
            val f = if (max > 0) (usd / max).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(coder.architect))
        }
        if (extra != null) Text(extra, style = Mono.tiny.copy(fontFeatureSettings = "tnum"), color = coder.muted)
    }
}
