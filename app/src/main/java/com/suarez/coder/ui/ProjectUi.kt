package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.Close
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
        Row(Modifier.fillMaxHeight()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp).navigationBarsPadding()) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(coder.architect, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("Sohbetler", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Ara: başlık, repo, içerik") },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = coder.border),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = coder.accentSoft,
                        border = BorderStroke(1.dp, coder.borderHighlight),
                        modifier = Modifier.weight(1f).clickable { onNewChat() },
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text("+ Yeni sohbet", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, coder.border),
                        modifier = Modifier.weight(1f).clickable { onDashboard() },
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text("📊 Maliyet Panosu", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (list.isEmpty()) item {
                        Text(if (query.isBlank()) "Henüz kayıtlı sohbet yok." else "Sonuç yok.", style = MaterialTheme.typography.bodySmall,
                            color = coder.muted, modifier = Modifier.padding(8.dp))
                    }
                    items(list, key = { it.id }) { s ->
                        val active = s.id == vm.currentSessionId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) coder.accentSoft else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (active) coder.borderHighlight else coder.border),
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(s.id) },
                        ) {
                            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(3.dp).fillMaxHeight().background(if (active) coder.architect else coder.border))
                                Column(Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
                                    Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        s.repo.substringAfterLast('/').ifBlank { null }?.let { Badge(it, coder.architect) }
                                        Text(fmt.format(Date(s.updatedAt)), style = Mono.tiny, color = coder.muted)
                                    }
                                }
                                Text(
                                    Pricing.formatUsd(s.costUsd),
                                    style = Mono.tiny, color = coder.cost,
                                    modifier = Modifier.background(coder.cost.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                                IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Default.Close, "Sil", tint = coder.muted, modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
                HorizontalDivider(color = coder.border)
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(coder.border))
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
    var editing by remember { mutableStateOf<ProjectProfile?>(null) }
    val activeId = vm.settings.activeProfileId
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Projeler", style = MaterialTheme.typography.titleMedium)
        vm.profiles.forEach { p ->
            val active = p.id == activeId
            StudioCard(
                elevated = active,
                modifier = Modifier.fillMaxWidth().clickable { vm.activateProfile(p.id); onDone() },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (active) "●" else "○", color = if (active) coder.architect else coder.muted)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p.repo.ifBlank { "(repo yok)" }, style = Mono.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (active) Badge("etkin", coder.architect)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.branch.ifBlank { null }?.let { Badge(it, coder.editor) }
                            Text(
                                listOfNotNull(
                                    p.architectModel.ifBlank { null }?.let { "Mimar ${shortModel(it.substringAfter('/'))}" },
                                    p.editorModel.ifBlank { null }?.let { "Uyg ${shortModel(it.substringAfter('/'))}" },
                                    p.contextFiles.size.takeIf { it > 0 }?.let { "📎 $it" },
                                    p.instructions.ifBlank { null }?.let { "talimat ✓" },
                                ).joinToString(" · ").ifBlank { "varsayılan ayarlar" },
                                style = Mono.tiny, color = coder.muted, maxLines = 1,
                            )
                        }
                    }
                    TextButton(onClick = { editing = p }) { Text("✎") }
                }
            }
        }
        OutlinedButton(onClick = { editing = ProjectProfile() }, modifier = Modifier.fillMaxWidth()) { Text("+ Yeni profil") }
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
                OutlinedTextField(repo, { repo = it }, label = { Text("Repo (sahip/isim)") }, singleLine = true, textStyle = Mono.body,
                    shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = coder.border))
                OutlinedTextField(branch, { branch = it }, label = { Text("Varsayılan branch (isteğe bağlı)") }, singleLine = true, textStyle = Mono.body,
                    shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = coder.border))
                OutlinedTextField(instr, { instr = it }, label = { Text("Proje talimatları") }, minLines = 3,
                    shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = coder.border))
                OutlinedTextField(arch, { arch = it }, label = { Text("Mimar modeli (boş = genel ayar)") }, singleLine = true, textStyle = Mono.small,
                    placeholder = { Text("gemini/gemini-4-hydrogen", style = Mono.small) },
                    shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = coder.border))
                OutlinedTextField(editor, { editor = it }, label = { Text("Uygulayıcı modeli (boş = genel ayar)") }, singleLine = true, textStyle = Mono.small,
                    placeholder = { Text("gemini/gemini-3.8-flash", style = Mono.small) },
                    shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = coder.border))
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
    LaunchedEffect(Unit) { data = vm.costBreakdown() }
    val rate = vm.settings.usdTry
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Kapat") }
                    Text("Maliyet Panosu", style = MaterialTheme.typography.titleLarge)
                }
                val d = data
                if (d == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Hesaplanıyor…", color = coder.muted) }
                    return@Column
                }
                val maxRole = d.byRole.maxOfOrNull { it.second } ?: 0.0
                val maxRepo = d.byRepo.maxOfOrNull { it.second } ?: 0.0
                val maxMonth = d.byMonth.maxOfOrNull { it.second } ?: 0.0
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("Toplam", d.totalUsd, rate, Modifier.weight(1f))
                        StatCard("Bu ay", d.monthUsd, rate, Modifier.weight(1f))
                    }
                    StudioCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Önbellek isabeti", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text(d.cacheHitRate?.let { "%${(it * 100).toInt()}" } ?: "–",
                                style = Mono.body.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
                                color = cacheColor(d.cacheHitRate))
                        }
                        Spacer(Modifier.height(8.dp))
                        val hr = d.cacheHitRate?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                        Box(Modifier.fillMaxWidth().height(10.dp).background(coder.border, RoundedCornerShape(5.dp))) {
                            Box(Modifier.fillMaxWidth(hr).height(10.dp).background(cacheColor(d.cacheHitRate), RoundedCornerShape(5.dp)))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Önbellekten okunan giriş tokenı oranı (karar ve depolama hariç).", style = MaterialTheme.typography.bodySmall, color = coder.muted)
                    }
                    StudioCard {
                        Text("Repo bazında", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        if (d.byRepo.isEmpty()) Text("Kayıt yok.", color = coder.muted)
                        d.byRepo.forEach { (repo, usd, hit) ->
                            BarRow(repo, usd, maxRepo, rate, hit?.let { "önbellek %${(it * 100).toInt()}" }, coder.architect)
                        }
                    }
                    StudioCard {
                        Text("Rol dağılımı", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        d.byRole.forEach { (role, usd) -> BarRow(role, usd, maxRole, rate, null, coder.editor) }
                    }
                    StudioCard {
                        Text("Aylar", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        d.byMonth.forEach { (m, usd) -> BarRow(m, usd, maxMonth, rate, null, coder.cost) }
                    }
                    Text("Tutarlar kayıt anındaki fiyatlarla hesaplanır; TL karşılığı Ayarlar'daki kurdan (1 USD = ₺$rate).",
                        style = MaterialTheme.typography.bodySmall, color = coder.muted)
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, usd: Double, rate: Double, modifier: Modifier) {
    StudioCard(elevated = true, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = coder.muted)
        Spacer(Modifier.height(6.dp))
        Text(Pricing.formatUsd(usd), style = Mono.body.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize), color = coder.cost)
        Text(Pricing.formatTry(usd * rate), style = Mono.small, color = coder.muted)
    }
}

@Composable
private fun cacheColor(rate: Double?) = when {
    rate == null -> coder.muted
    rate >= 0.6 -> coder.cacheGood
    rate >= 0.3 -> coder.cacheMid
    else -> coder.warn
}

@Composable
private fun BarRow(label: String, usd: Double, max: Double, rate: Double, extra: String?, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Mono.small, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Pricing.formatUsd(usd), style = Mono.small, color = coder.cost)
            Text(" · " + Pricing.formatTry(usd * rate), style = Mono.tiny, color = coder.muted)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).background(coder.border, RoundedCornerShape(4.dp))) {
            val f = if (max > 0) (usd / max).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(f).height(8.dp).background(color, RoundedCornerShape(4.dp)))
        }
        if (extra != null) {
            Spacer(Modifier.height(2.dp))
            Text(extra, style = Mono.tiny, color = coder.muted)
        }
    }
}
