package com.suarez.coder.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.navigationBarsPadding
import com.suarez.coder.skills.SkillImporter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.suarez.coder.cloudtools.CloudToolRunner
import com.suarez.coder.cloudtools.CloudWorkflowTemplates
import com.suarez.coder.skills.Skill
import java.io.File

/** Outlined "extension" (puzzle piece); drawn here because material-icons-core does not ship it. */
val ExtensionOutlined: ImageVector by lazy {
    ImageVector.Builder("ExtensionOutlined", 24.dp, 24.dp, 24f, 24f).addPath(
        addPathNodes(
            "M10.5,4.5c0.28,0 0.5,0.22 0.5,0.5v2h6v6h2c0.28,0 0.5,0.22 0.5,0.5s-0.22,0.5 -0.5,0.5h-2v6h-2.12c-0.68,-1.75 -2.39,-3 -4.38,-3s-3.7,1.25 -4.38,3H4v-2.12c1.75,-0.68 3,-2.39 3,-4.38S5.76,9.2 4.01,8.52L4,6.4h6V5C10,4.72 10.22,4.5 10.5,4.5M10.5,2.5C9.12,2.5 8,3.62 8,5H4c-1.1,0 -1.99,0.9 -1.99,2v3.8h0.29c1.49,0 2.7,1.21 2.7,2.7s-1.21,2.7 -2.7,2.7H2V20c0,1.1 0.9,2 2,2h3.8v-0.3c0,-1.49 1.21,-2.7 2.7,-2.7s2.7,1.21 2.7,2.7V22H17c1.1,0 2,-0.9 2,-2v-4c1.38,0 2.5,-1.12 2.5,-2.5S20.38,11 19,11V7c0,-1.1 -0.9,-2 -2,-2h-4C13,3.62 11.88,2.5 10.5,2.5L10.5,2.5z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

private val strong @Composable get() = MaterialTheme.colorScheme.onSurface
private val weak @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val line @Composable get() = MaterialTheme.colorScheme.outlineVariant

@Composable
private fun MonoCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, line),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Tag(text: String) = Surface(shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, line), color = Color.Transparent) {
    Text(text, style = Mono.tiny, color = strong, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
}

private const val NEW_SKILL = "---\nname: yeni-yetenek\ndescription: Bir satırlık açıklama (sistem talimatına yalnızca bu girer)\ntriggers: [anahtar, kelime]\n---\n\n# Talimatlar\n\n1. ...\n"

@Composable
fun SkillsPage(vm: ChatViewModel) {
    LaunchedEffect(Unit) { vm.refreshSkills(fetchRepo = true); vm.refreshCloudTools() }
    var selectedTab by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) } // (markdown, fallback name)
    var deleting by remember { mutableStateOf<Skill?>(null) }
    var importing by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<Pair<String, List<com.suarez.coder.skills.SkillSafety.Finding>>?>(null) }
    Column(Modifier.statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Yetenekler (Skills)", "Blueprint'ler").forEachIndexed { index, label ->
                val selected = selectedTab == index
                Surface(
                    onClick = { selectedTab = index },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) strong else Color.Transparent,
                    border = BorderStroke(1.dp, if (selected) strong else line),
                ) {
                    Text(
                        label,
                        style = Mono.tiny,
                        color = if (selected) MaterialTheme.colorScheme.surface else strong,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (selectedTab == 1) {
            MonoCard {
                Text("Blueprint'ler", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = strong)
                Text("Çok-dosyalı başarılı turlardan kaydedilen parametrik şablonlar.", style = MaterialTheme.typography.bodySmall, color = weak)
            }
            return@Column
        }
        MonoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Yetenekler", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = strong, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.refreshSkills(fetchRepo = true) }) { Icon(Icons.Outlined.Refresh, "Yenile", tint = weak) }
                IconButton(onClick = { importing = true }) { Icon(Icons.Outlined.Star, "GitHub'dan içe aktar", tint = strong) }
                IconButton(onClick = { editing = NEW_SKILL to "" }) { Icon(Icons.Outlined.Add, "Yeni yetenek", tint = strong) }
            }
            Text("Sistem talimatına yalnızca ad ve tek satır açıklama girer; tam talimat ajan load_skill çağırınca yüklenir. " +
                "Repo yetenekleri .coder/skills/<ad>/SKILL.md yolundan okunur ve aynı addaki genel yeteneği ezer. Sohbette /ad ile çağrılır.",
                style = MaterialTheme.typography.bodySmall, color = weak)
            if (vm.skills.isEmpty()) Text("Yetenek yok.", style = MaterialTheme.typography.bodySmall, color = weak)
            vm.skills.forEach { s ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("/" + s.name, style = Mono.body, color = strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                                Tag(s.source.label)
                                if (s.source == Skill.Source.AUTO_SYNTHESIZED) Tag("Otomatik Sentez")
                                if (s.status == Skill.Status.DRAFT) Tag("Taslak")
                                if (s.status == Skill.Status.REVIEW_REQUIRED) Tag("İnceleme Gerekli")
                                s.workflow?.let { Tag("bulut: $it") }
                                if (s.origin?.startsWith("evolution:") == true) Tag("öz-gelişim önerisi") else if (s.origin != null) Tag("GitHub")
                                if (s.hasScripts) Tag("betik içerir")
                                val findings = remember(s) { if (s.origin != null) com.suarez.coder.skills.SkillSafety.scan(s) else emptyList() }
                                if (findings.isNotEmpty()) Box(Modifier.clickable { warnings = s.name to findings }) { Tag("uyarı ${findings.size}") }
                            }
                            Text(s.description, style = MaterialTheme.typography.bodySmall, color = weak, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(s.statLine(), style = Mono.tiny, color = weak)
                            s.reviewNote?.let { Text(it, style = Mono.tiny, color = weak, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        if (s.origin != null && !s.origin.startsWith("evolution:")) IconButton(onClick = { vm.updateSkill(s.name) }) { Icon(Icons.Outlined.Refresh, "Kaynaktan güncelle", tint = weak) }
                        if (s.source != Skill.Source.REPO) {
                            IconButton(onClick = { editing = s.toMarkdown() to s.name }) { Icon(Icons.Outlined.Edit, "Düzenle", tint = weak) }
                            IconButton(onClick = { deleting = s }) { Icon(Icons.Outlined.Delete, "Sil", tint = weak) }
                        }
                        Switch(checked = s.enabled, onCheckedChange = { vm.setSkillEnabled(s.name, it) }, colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.surface, checkedTrackColor = strong,
                            uncheckedThumbColor = weak, uncheckedTrackColor = MaterialTheme.colorScheme.surface, uncheckedBorderColor = line))
                    }
                }
            }
        }

        MonoCard {
            Text("Bulut araçları (GitHub Actions)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = strong)
            val repo = vm.settings.contextRepo.ifBlank { vm.settings.defaultRepo }
            Text(if (repo.isBlank()) "Önce bir repo seçin (profil veya Varsayılan repo)." else "Repo: $repo · ajan run_cloud_tool ile çalıştırır; workflow yoksa kendisi kurar.",
                style = MaterialTheme.typography.bodySmall, color = weak)
            val installs = vm.cloudInstalls
            if (installs == null && repo.isNotBlank()) Text("Kontrol ediliyor…", style = MaterialTheme.typography.bodySmall, color = weak)
            installs?.forEach { i ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.tool.title, style = MaterialTheme.typography.bodyLarge, color = strong)
                        Text(".github/workflows/${i.tool.file} · ${i.state.label}", style = Mono.tiny, color = weak)
                    }
                    OutlinedButton(onClick = { vm.installCloudTool(i.tool) }, border = BorderStroke(1.dp, line),
                        enabled = i.state != CloudWorkflowTemplates.InstallState.CURRENT) {
                        Text(if (i.state == CloudWorkflowTemplates.InstallState.MISSING) "Repoya Kur" else "Güncelle", color = strong)
                    }
                }
            }
            vm.skillsStatus?.let { Text(it, style = Mono.small, color = weak) }
        }
    }

    editing?.let { (md, fallback) ->
        var text by remember(md) { mutableStateOf(md) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            icon = { Icon(Icons.Outlined.Create, null) },
            title = { Text("SKILL.md") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it; error = null }, textStyle = Mono.small, minLines = 10,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { error = vm.saveSkill(text, fallback); if (error == null) editing = null }) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Vazgeç") } },
        )
    }
    if (importing) ImportDialog(vm) { importing = false; vm.clearCandidates() }
    warnings?.let { (name, list) ->
        AlertDialog(
            onDismissRequest = { warnings = null },
            title = { Text("/$name: güvenlik uyarıları") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bu yetenek ajana talimat olarak verilir. Aşağıdaki satırları okuyup güveniyorsanız açın; değilse silin.",
                        style = MaterialTheme.typography.bodySmall, color = weak)
                    list.forEach { f ->
                        Text("${f.file}${if (f.line > 0) ":" + f.line else ""} · ${f.reason}", style = Mono.small, color = strong)
                        if (f.excerpt.isNotBlank()) Text(f.excerpt, style = Mono.tiny, color = weak)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { warnings = null }) { Text("Kapat") } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("/${s.name} silinsin mi?") },
            text = { Text(if (s.source == Skill.Source.BUILTIN) "Yerleşik yetenek silinir; kapatmak için anahtarı da kullanabilirsiniz." else "Genel yetenek silinir.") },
            confirmButton = { TextButton(onClick = { vm.deleteSkill(s.name); deleting = null }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Vazgeç") } },
        )
    }
}

// ---- Import from GitHub ----

/** Starred repositories (skill-like ones first) or a pasted link → the SKILL.md files found → import selected. */
@Composable
private fun ImportDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { if (vm.starred == null) vm.loadStarred() }
    val candidates = vm.candidates
    LaunchedEffect(candidates) { picked = candidates?.map { it.path }?.toSet().orEmpty() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    IconButton(onClick = { if (candidates != null) vm.clearCandidates() else onDismiss() }) {
                        Icon(if (candidates != null) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Close, "Geri", tint = strong)
                    }
                    Text(if (candidates != null) "Bulunan yetenekler" else "GitHub'dan içe aktar", style = MaterialTheme.typography.titleLarge, color = strong)
                }
                vm.importBusy?.let { Text(it + "…", style = Mono.small, color = weak) }
                vm.importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = weak) }
                Spacer(Modifier.size(8.dp))
                if (candidates == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(url, { url = it }, singleLine = true, textStyle = Mono.small, modifier = Modifier.weight(1f),
                            label = { Text("GitHub adresi (repo, klasör veya SKILL.md)") })
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.scanForSkills(url) }, enabled = url.isNotBlank() && vm.importBusy == null,
                            border = BorderStroke(1.dp, line)) { Text("Tara", color = strong) }
                    }
                    Spacer(Modifier.size(12.dp))
                    Text("Önerilen kaynaklar", style = MaterialTheme.typography.titleSmall, color = strong)
                    SkillImporter.RECOMMENDED.forEach { r ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = vm.importBusy == null) { vm.scanForSkills(r.repo) }.padding(vertical = 6.dp)) {
                            Text(r.repo, style = Mono.small, color = strong)
                            Text(r.note, style = MaterialTheme.typography.bodySmall, color = weak, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Yıldızlı repolar", style = MaterialTheme.typography.titleSmall, color = strong, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val all = vm.starred.orEmpty()
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(all.joinToString("\n") { r ->
                                r.fullName + if (r.description.isNotBlank()) " - " + r.description.take(120) else "" }))
                        }, enabled = !vm.starred.isNullOrEmpty()) { Text("Listeyi kopyala", color = strong) }
                    }
                    OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filtrele") },
                        textStyle = Mono.small, modifier = Modifier.fillMaxWidth())
                    val list = vm.starred.orEmpty().filter { filter.isBlank() || it.fullName.contains(filter, true) || it.description.contains(filter, true) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(list, key = { it.fullName }) { r ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = vm.importBusy == null) { vm.scanForSkills(r.fullName) }.padding(vertical = 10.dp)) {
                                Text(r.fullName, style = Mono.body, color = strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (r.description.isNotBlank()) Text(r.description, style = MaterialTheme.typography.bodySmall, color = weak,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider(color = line)
                        }
                    }
                } else {
                    val existing = vm.skills.map { it.name }.toSet()
                    LazyColumn(Modifier.weight(1f)) {
                        items(candidates, key = { it.path }) { c ->
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Checkbox(checked = c.path in picked, onCheckedChange = { on -> picked = if (on) picked + c.path else picked - c.path },
                                    colors = CheckboxDefaults.colors(checkedColor = strong, checkmarkColor = MaterialTheme.colorScheme.surface, uncheckedColor = weak))
                                Column(Modifier.weight(1f)) {
                                    Text("/" + c.name, style = Mono.body, color = strong)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                                        if (c.files.isNotEmpty()) Tag("${c.files.size} dosya")
                                        if (c.hasScripts) Tag("betik içerir")
                                        if (c.name in existing) Tag("üzerine yazılır")
                                    }
                                    Text(c.description, style = MaterialTheme.typography.bodySmall, color = weak, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(c.path, style = Mono.tiny, color = weak, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HorizontalDivider(color = line)
                        }
                    }
                    if (candidates.any { it.hasScripts }) Text("Betikli yetenekler bilgisayar varsayar; Coder betikleri gerekirse GitHub Actions'ta (toolbox) çalıştırır.",
                        style = MaterialTheme.typography.bodySmall, color = weak, modifier = Modifier.padding(vertical = 6.dp))
                    OutlinedButton(onClick = { vm.importSkills(candidates.filter { it.path in picked }) },
                        enabled = picked.isNotEmpty() && vm.importBusy == null, border = BorderStroke(1.dp, line),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("Seçilenleri içe aktar (${picked.size})", color = strong)
                    }
                }
            }
        }
    }
}

// ---- Chat: cloud tool result card ----

/** Shown under a run_cloud_tool result: preview (tap for full screen), "Revize Et" and asset saving. */
@Composable
fun CloudResultCards(vm: ChatViewModel, toolText: String) {
    if (!toolText.contains("[[cloud-output")) return
    CloudToolRunner.outputs(toolText).forEach { out -> CloudResultCard(vm, out) }
}

@Composable
private fun CloudResultCard(vm: ChatViewModel, out: CloudToolRunner.Companion.Output) {
    val preview = remember(out.dir) { out.preview }
    val assets = remember(out.dir) { out.assets }
    var full by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val bitmap = remember(preview) {
        preview?.let { f ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val sample = generateSequence(1) { it * 2 }.first { o.outWidth / it <= 1200 && o.outHeight / it <= 1200 }
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    MonoCard {
        Text("Bulut çıktısı · ${out.tool}", style = MaterialTheme.typography.titleSmall, color = strong)
        if (!out.dir.isDirectory) {
            Text("Çıktılar artık cihazda yok.", style = MaterialTheme.typography.bodySmall, color = weak)
            return@MonoCard
        }
        bitmap?.let {
            Image(it, "Önizleme", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .clickable { full = true })
        }
        if (preview != null) {
            OutlinedButton(onClick = { vm.attachPreview(preview); message = "Görsel mesaj kutusuna eklendi; isteğini yazıp gönder." },
                border = BorderStroke(1.dp, line), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = strong); Spacer(Modifier.width(6.dp))
                Text("Revize Et (Görseli Ekle)", color = strong)
            }
        }
        assets.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.name} · ${f.length() / 1024} KB", style = Mono.small, color = strong, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { message = vm.saveAsset(f) }) { Text("İndir", color = strong) }
                IconButton(onClick = { message = vm.shareAsset(f) }) { Icon(Icons.Outlined.Share, "Paylaş", tint = weak) }
            }
        }
        if (assets.isEmpty() && preview == null) Text("Çıktı dosyası yok.", style = MaterialTheme.typography.bodySmall, color = weak)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = weak) }
    }
    if (full && bitmap != null) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
                Image(bitmap, "Önizleme", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clickable { full = false })
                IconButton(onClick = { full = false }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.Close, "Kapat", tint = Color.White) }
            }
        }
    }
}

@Suppress("unused")
private fun File.isImage() = extension.lowercase() in setOf("png", "jpg", "jpeg")
