package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suarez.coder.cost.Pricing
import com.suarez.coder.evolution.ClusterStatus
import com.suarez.coder.evolution.CostToGreenScorer
import com.suarez.coder.evolution.EvoTier
import com.suarez.coder.evolution.ErrorCluster
import com.suarez.coder.evolution.EvolutionLogEntry
import com.suarez.coder.evolution.PendingSelfPr
import com.suarez.coder.evolution.PrState
import com.suarez.coder.evolution.RuleSource
import com.suarez.coder.evolution.ScopedRule
import com.suarez.coder.evolution.TaskDomain
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outlined "auto awesome" sparkle (material-icons-core does not ship it; drawn here to avoid the extended icon set). */
val AutoAwesomeOutlined: ImageVector by lazy {
    ImageVector.Builder("AutoAwesomeOutlined", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 9f); lineToRelative(1.25f, -2.75f); lineTo(23f, 5f); lineToRelative(-2.75f, -1.25f); lineTo(19f, 1f)
            lineToRelative(-1.25f, 2.75f); lineTo(15f, 5f); lineToRelative(2.75f, 1.25f); close()
            moveTo(19f, 15f); lineToRelative(-1.25f, 2.75f); lineTo(15f, 19f); lineToRelative(2.75f, 1.25f); lineTo(19f, 23f)
            lineToRelative(1.25f, -2.75f); lineTo(23f, 19f); lineToRelative(-2.75f, -1.25f); close()
            moveTo(11.5f, 9.5f); lineTo(9f, 4f); lineTo(6.5f, 9.5f); lineTo(1f, 12f); lineToRelative(5.5f, 2.5f); lineTo(9f, 20f)
            lineToRelative(2.5f, -5.5f); lineTo(17f, 12f); close()
            moveTo(9.99f, 12.99f); lineTo(9f, 15.17f); lineToRelative(-0.99f, -2.18f); lineTo(5.83f, 12f); lineToRelative(2.18f, -0.99f)
            lineTo(9f, 8.83f); lineToRelative(0.99f, 2.18f); lineTo(12.17f, 12f); close()
        }
    }.build()
}

/** Monochrome palette for the evolution screen: surface tones only, no accent colours. */
private object Mono2 {
    val strong @Composable get() = MaterialTheme.colorScheme.onSurface
    val weak @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val line @Composable get() = MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun EvoCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Mono2.line),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun CardTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Mono2.strong)

@Composable
private fun MonoSwitch(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Mono2.strong else Mono2.weak)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mono2.weak) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.surface, checkedTrackColor = Mono2.strong,
            uncheckedThumbColor = Mono2.weak, uncheckedTrackColor = MaterialTheme.colorScheme.surface, uncheckedBorderColor = Mono2.line,
        ))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonoChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(8.dp),
                color = if (on) Mono2.strong else Color.Transparent,
                border = BorderStroke(1.dp, if (on) Mono2.strong else Mono2.line),
            ) {
                Text(label, style = Mono.tiny, color = if (on) MaterialTheme.colorScheme.surface else Mono2.strong,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
fun EvolutionPage(vm: ChatViewModel) {
    LaunchedEffect(Unit) { vm.refreshEvolution(); vm.refreshSkills() }
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MonoChips(listOf("Özet & PR", "Kümeler", "Hafıza", "Modeller (CTG)", "Motor"), tab) { tab = it }
        when (tab) {
            0 -> SummaryTab(vm)
            1 -> ClustersTab(vm)
            2 -> MemoryTab(vm)
            3 -> ModelsTab(vm)
            else -> MotorTab(vm)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SummaryTab(vm: ChatViewModel) {
    val s = vm.settings
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        vm.evoPending?.let { PendingPrCard(vm, it) }
        ManagerSection(vm)

        EvoCard {
            CardTitle("Otomasyon")
            var enabled by remember { mutableStateOf(s.evoEnabled) }
            var k1 by remember { mutableStateOf(s.evoMemory) }
            var k2 by remember { mutableStateOf(s.evoRouting) }
            var k3 by remember { mutableStateOf(s.evoSelfPr) }
            var wifi by remember { mutableStateOf(s.evoWifiOnly) }
            MonoSwitch("Arka planda otomatik öz-gelişim", "Seans bitince boşta değerlendirilir; sohbeti bekletmez.", enabled) { enabled = it; s.evoEnabled = it; vm.refreshSettings() }
            HorizontalDivider(color = Mono2.line)
            MonoSwitch("Hafıza ve dersler (K1)", "Repo başına kısa dersler; sabit bağlama eklenir.", k1, enabled) { k1 = it; s.evoMemory = it }
            MonoSwitch("Adaptif yönlendirme (K2)", "Sınırlı ve geri alınabilir eşik ve ipuçları.", k2, enabled) { k2 = it; s.evoRouting = it }
            MonoSwitch("Kendi koduna PR aç (K3)", "Küme eşiği dolunca test önce, CI yeşilse PR; merge her zaman sizin onayınızla.", k3, enabled) { k3 = it; s.evoSelfPr = it }
            MonoSwitch("Sadece Wi-Fi'de çalıştır", null, wifi, enabled) { wifi = it; s.evoWifiOnly = it }
            var notesSync by remember { mutableStateOf(s.syncProjectNotes) }
            MonoSwitch("NOTES.md sonraki commite eklensin", "Kapalıyken hiçbir NOTES commit'i yok. Açıkken yalnızca senin istediğin commite eklenir; merge edilmez.", notesSync, enabled) {
                notesSync = it; s.syncProjectNotes = it
            }
            var sessions by remember { mutableStateOf(s.clusterMinSessions.toString()) }
            var floor by remember { mutableStateOf(s.clusterMinCostUsd.toString()) }
            OutlinedTextField(sessions, {
                sessions = it.filter { c -> c.isDigit() }.take(2)
                sessions.toIntOrNull()?.let { n -> s.clusterMinSessions = n }
            }, singleLine = true, textStyle = Mono.body, label = { Text("Self-PR küme eşiği (farklı seans)") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(floor, {
                floor = it
                it.replace(',', '.').toDoubleOrNull()?.let { v -> s.clusterMinCostUsd = v }
            }, singleLine = true, textStyle = Mono.body, label = { Text("Self-PR küme eşiği (USD)") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text("2 farklı seans, bu maliyet veya 3 başarısız tur. Tek seans PR açmaz.") })
            HorizontalDivider(color = Mono2.line)
            var ctx by remember { mutableStateOf(s.contextualRouting) }
            var pe by remember { mutableStateOf(s.promptEvolution) }
            var pm by remember { mutableStateOf(s.procedureMining) }
            var eu by remember { mutableStateOf(s.evalOnUpdate) }
            MonoSwitch("Bağlamsal model seçimi", "Görevin zorluğu, dosya sayısı ve diline göre model seçer; her karar olasılığıyla kaydedilir, " +
                "yeni seçim kuralları geçmiş veride çevrimdışı ölçülür.", ctx, enabled) { ctx = it; s.contextualRouting = it }
            MonoSwitch("İstem evrimi", "Son hatalardan istemin bir bölümünü yeniden yazar; yalnızca sınavı kazanırsa kullanılır.", pe, enabled) { pe = it; s.promptEvolution = it }
            MonoSwitch("Prosedür hafızası", "Tekrarlanan başarılı adım dizileri beceri adayı olur; sınavdan geçerse etkinleşir.", pm, enabled) { pm = it; s.procedureMining = it }
            MonoSwitch("Güncellemeden sonra ölçüm", "Uygulama güncellenince sabit değerlendirme seti bir kez çalışır (haftalık sınav bütçesinden).", eu, enabled) { eu = it; s.evalOnUpdate = it }
            HorizontalDivider(color = Mono2.line)
            Text("Mimar (Kilitli): ${s.architectModel}", style = Mono.tiny, color = Mono2.strong)
            Text("Evrim modeli Mimar seçimini okumaz ve yazmaz. Gemini, Sağlayıcılar'daki mevcut anahtarı kullanır.", style = Mono.tiny, color = Mono2.weak)
            var provider by remember { mutableStateOf(s.evoProvider) }
            MonoChips(listOf("OpenRouter", "Gemini"), if (provider == "GEMINI") 1 else 0) { i ->
                provider = if (i == 1) "GEMINI" else "OPENROUTER"
                s.evoProvider = provider
            }
            if (provider == "GEMINI") {
                var gemini by remember { mutableStateOf(s.evoGeminiModel) }
                OutlinedTextField(gemini, {
                    gemini = it
                    s.evoGeminiModel = it.ifBlank { "gemini-2.5-flash" }
                }, singleLine = true, textStyle = Mono.body, label = { Text("Gemini modeli") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(if (s.geminiKey.isBlank()) "Gemini anahtarı yok. Sağlayıcılar ekranındaki anahtar alanını doldurun."
                        else "Doğrudan Gemini API. Sohbet ve Mimar modellerinden ayrıdır.")
                    })
                val geminiIds = (s.modelCatalog.filter { it.startsWith("gemini/") }.map { it.removePrefix("gemini/") } +
                    listOf("gemini-2.5-flash", "gemini-2.5-pro")).distinct()
                MonoChips(geminiIds.take(12), geminiIds.indexOf(gemini)) { i ->
                    val id = geminiIds[i]
                    gemini = id
                    s.evoGeminiModel = id
                }
            } else {
                var model by remember { mutableStateOf(s.evoModel) }
                val known = remember { vm.agent.openRouterModels().keys.sorted() }
                var query by remember { mutableStateOf("") }
                val valid = known.isEmpty() || model.trim().removePrefix("openrouter/") in known
                OutlinedTextField(model, { model = it; s.evoModel = it.ifBlank { "nvidia/nemotron-3-ultra" } }, singleLine = true, textStyle = Mono.body,
                    label = { Text("OpenRouter modeli") }, modifier = Modifier.fillMaxWidth(), isError = !valid,
                    supportingText = {
                        Text(when {
                            !valid -> "Bu kimlik OpenRouter listesinde yok."
                            known.isEmpty() -> "Liste yok: Sağlayıcılar > OpenRouter > Modelleri getir."
                            else -> "Varsayılan nvidia/nemotron-3-ultra. Mimar modeli değişmez."
                        })
                    })
                if (known.isNotEmpty()) {
                    OutlinedTextField(query, { query = it }, singleLine = true, textStyle = Mono.small, label = { Text("Model ara") }, modifier = Modifier.fillMaxWidth())
                    val shown = (listOf("nvidia/nemotron-3-ultra").filter { it in known } + known.filter { query.isBlank() || it.contains(query, true) })
                        .distinct().take(12)
                    if (shown.isNotEmpty()) {
                        MonoChips(shown, shown.indexOf(model)) { i ->
                            model = shown[i]
                            s.evoModel = shown[i]
                        }
                    }
                }
                if (s.openrouterKey.isBlank()) Text("OpenRouter anahtarı gerekli (Sağlayıcılar & API).", style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
            }
            var budget by remember { mutableStateOf(s.evoDailyBudget.toString()) }
            OutlinedTextField(budget, { budget = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> s.evoDailyBudget = v } }, singleLine = true,
                textStyle = Mono.body, label = { Text("Günlük evrim bütçesi (USD)") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.evoRunNow() }, enabled = !vm.evoBusy, border = BorderStroke(1.dp, Mono2.line)) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp), tint = Mono2.strong)
                    Spacer(Modifier.width(6.dp))
                    Text("Son seansı şimdi değerlendir", color = Mono2.strong)
                }
            }
            vm.evoStatus?.let { Text(it, style = Mono.small, color = Mono2.weak) }
        }

        EvoCard {
            CardTitle("Özet")
            vm.evoSummary().forEach { (k, v) ->
                Row {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = Mono2.weak, modifier = Modifier.weight(1f))
                    Text(v, style = Mono.small, color = Mono2.strong)
                }
            }
        }

        EvoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle("Günlük")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.refreshEvolution() }) { Icon(Icons.Outlined.Refresh, "Yenile", tint = Mono2.weak) }
            }
            if (vm.evoLog.isEmpty()) Text("Henüz kayıt yok.", style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
            vm.evoLog.take(60).forEach { e -> LogRow(vm, e) }
        }
    }
}

@Composable
private fun LogRow(vm: ChatViewModel, e: EvolutionLogEntry) {
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale("tr")) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Mono2.line), color = Color.Transparent) {
                Text(e.tier.label, style = Mono.tiny, color = Mono2.strong, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(fmt.format(Date(e.at)), style = Mono.tiny, color = Mono2.weak)
            if (e.costUsd > 0) Text(" · " + Pricing.formatUsd(e.costUsd), style = Mono.tiny, color = Mono2.weak)
            Spacer(Modifier.weight(1f))
            if (e.tier == EvoTier.ROUTING && e.undo != null && !e.undone) {
                TextButton(onClick = { vm.evoUndo(e) }) { Text("Geri Al", color = Mono2.strong) }
            } else if (e.undone) Text("geri alındı", style = Mono.tiny, color = Mono2.weak)
        }
        Text(e.summary, style = MaterialTheme.typography.bodySmall, color = Mono2.strong)
        Spacer(Modifier.size(4.dp))
        HorizontalDivider(color = Mono2.line)
    }
}

@Composable
private fun PendingPrCard(vm: ChatViewModel, p: PendingSelfPr) {
    var reviewing by remember { mutableStateOf(false) }
    var confirmReject by remember { mutableStateOf(false) }
    EvoCard {
        when (p.state) {
            PrState.PENDING, PrState.CONFLICT -> {
                Text("PR #${p.number} oluşturuldu: ${p.title}. Merge edip yeni release APK yayınlayayım mı?",
                    style = MaterialTheme.typography.bodyLarge, color = Mono2.strong)
                Text(p.rootCause, style = MaterialTheme.typography.bodySmall, color = Mono2.weak, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p.clusterKey.isNotBlank()) Text(p.clusterKey, style = Mono.tiny, color = Mono2.weak, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.evalTotal > 0) {
                    Surface(shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Mono2.line), color = Color.Transparent) {
                        Text("Eval: ${p.evalPassed}/${p.evalTotal} · +${p.evalAdded} yeni test · ${p.evalRegressions} gerileme",
                            style = Mono.tiny, color = Mono2.strong, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Text("${p.files.size} dosya · CI ${if (p.ciPassed) "Geçti" else "Bilinmiyor"} · " + Pricing.formatUsd(p.costUsd),
                    style = Mono.small, color = Mono2.weak)
                if (p.state == PrState.CONFLICT) Text("Merge çakışması: dal korunuyor; GitHub'da çözün veya reddedin.",
                    style = MaterialTheme.typography.bodySmall, color = Mono2.strong)
                Button(onClick = { vm.evoMerge() }, enabled = !vm.evoBusy && p.state == PrState.PENDING, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Mono2.strong, contentColor = MaterialTheme.colorScheme.surface)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Merge Et & Release Yayınla")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { reviewing = true }, border = BorderStroke(1.dp, Mono2.line), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = Mono2.strong); Spacer(Modifier.width(6.dp)); Text("İncele", color = Mono2.strong)
                    }
                    OutlinedButton(onClick = { confirmReject = true }, enabled = !vm.evoBusy, border = BorderStroke(1.dp, Mono2.line), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Close, null, Modifier.size(18.dp), tint = Mono2.strong); Spacer(Modifier.width(6.dp)); Text("Reddet", color = Mono2.strong)
                    }
                }
            }
            PrState.MERGED -> {
                Text("PR #${p.number} merge edildi: ${p.title}", style = MaterialTheme.typography.bodyLarge, color = Mono2.strong)
                if (p.apkUrl != null) ApkActions(vm, p.apkUrl)
                else Text(if (vm.evoBusy) "Release derlemesi bekleniyor." else "APK bağlantısı bulunamadı; Releases sayfasına bakın.",
                    style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
                TextButton(onClick = { vm.evoDismiss() }, enabled = !vm.evoBusy) { Text("Kartı kapat", color = Mono2.strong) }
            }
            PrState.REJECTED -> Unit
        }
    }
    if (reviewing) ReviewDialog(vm, p) { reviewing = false }
    if (confirmReject) AlertDialog(
        onDismissRequest = { confirmReject = false },
        icon = { Icon(Icons.Outlined.Info, null) },
        title = { Text("PR reddedilsin mi?") },
        text = { Text("PR kapatılır, ${p.branch} dalı silinir ve bu konu tekrar önerilmez.") },
        confirmButton = { TextButton(onClick = { confirmReject = false; vm.evoReject() }) { Text("Reddet") } },
        dismissButton = { TextButton(onClick = { confirmReject = false }) { Text("Vazgeç") } },
    )
}

@Composable
private fun ReviewDialog(vm: ChatViewModel, p: PendingSelfPr, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    var files by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(p.number) { files = vm.evoPrFiles() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PR #${p.number}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.title, style = MaterialTheme.typography.bodyLarge)
                Text("Kök neden", style = MaterialTheme.typography.labelLarge, color = Mono2.weak)
                Text(p.rootCause, style = MaterialTheme.typography.bodySmall)
                Text("Değişen dosyalar", style = MaterialTheme.typography.labelLarge, color = Mono2.weak)
                (files ?: p.files).forEach { Text(it, style = Mono.small) }
                Text("Dal: ${p.branch}", style = Mono.tiny, color = Mono2.weak)
                if (p.evalTotal > 0) Text("Golden Eval: ${p.evalPassed}/${p.evalTotal} (+${p.evalAdded} yeni vaka, ${p.evalRegressions} gerileme)", style = Mono.small)
            }
        },
        confirmButton = { TextButton(onClick = { uri.openUri(p.url) }) { Text("GitHub'da aç") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

@Composable
private fun ClustersTab(vm: ChatViewModel) {
    if (vm.evoClusters.isEmpty()) {
        Text("Henüz hata kümesi yok.", style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.evoClusters.forEach { cluster -> ClusterRow(vm, cluster) }
    }
}

@Composable
private fun ClusterRow(vm: ChatViewModel, c: ErrorCluster) {
    var open by remember(c.clusterKey) { mutableStateOf(false) }
    val usd = String.format(java.util.Locale.US, "%.2f", c.estimatedWastedCostUsd)
    val state = when (c.status) {
        ClusterStatus.COLLECTING -> "Toplanıyor"
        ClusterStatus.READY_FOR_PR -> "PR Hazır"
        ClusterStatus.PR_OPEN -> "PR Açık"
        ClusterStatus.RESOLVED -> "Çözüldü"
        ClusterStatus.DISMISSED -> "Yoksayıldı"
    }
    EvoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${c.clusterKey} · ${c.distinctSessionIds.size} seans · \$$usd kayıp · $state", style = Mono.small, color = Mono2.strong)
            }
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Kapat" else "Detay",
                tint = Mono2.weak, modifier = Modifier.clickable { open = !open }.padding(4.dp))
        }
        if (open) {
            c.sampleTraces.forEach { Text(it, style = Mono.tiny, color = Mono2.weak) }
            if (c.sampleTraces.isEmpty()) Text("Örnek iz yok.", style = Mono.tiny, color = Mono2.weak)
        }
        if (c.status != ClusterStatus.DISMISSED) {
            TextButton(onClick = { vm.evoDismissCluster(c.clusterKey) }) { Text("Yoksay", color = Mono2.strong) }
        }
    }
}

@Composable
private fun MemoryTab(vm: ChatViewModel) {
    var archived by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    MonoChips(listOf("Aktif", "Arşiv"), if (archived) 1 else 0) { archived = it == 1 }
    val rows = vm.evoRules.filter { it.archived == archived }
    if (rows.isEmpty()) Text(if (archived) "Arşiv boş." else "Bu repo için aktif kural yok.", style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rule -> RuleRow(vm, rule) }
        if (!archived) {
            OutlinedButton(onClick = { adding = true }, border = BorderStroke(1.dp, Mono2.line)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = Mono2.strong)
                Spacer(Modifier.width(6.dp))
                Text("Manuel kural", color = Mono2.strong)
            }
        }
    }
    if (adding) AddRuleDialog(onDismiss = { adding = false }) { globs, text ->
        adding = false
        vm.evoAddRule(globs, text)
    }
}

@Composable
private fun RuleRow(vm: ChatViewModel, rule: ScopedRule) {
    EvoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rule.scopeGlobs.joinToString(", "), style = Mono.tiny, color = Mono2.strong, modifier = Modifier.weight(1f))
            Text("${rule.hitCount}", style = Mono.small, color = Mono2.weak)
        }
        Text(rule.ruleText, style = MaterialTheme.typography.bodySmall, color = Mono2.strong)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rule.source == RuleSource.CI_VERIFIED) {
                Surface(shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Mono2.line), color = Color.Transparent) {
                    Text("CI Doğrulandı", style = Mono.tiny, color = Mono2.strong, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { if (rule.archived) vm.evoRestoreRule(rule.id) else vm.evoArchiveRule(rule.id) }) {
                Text(if (rule.archived) "Geri al" else "Arşivle", color = Mono2.strong)
            }
        }
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var globs by remember { mutableStateOf("**/*.kt") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manuel kural") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(globs, { globs = it }, singleLine = true, textStyle = Mono.body, label = { Text("Kapsam (glob)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it.take(220) }, textStyle = Mono.body, label = { Text("Kural (en fazla 220)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (text.trim().length >= 8) onAdd(globs, text.trim()) }) { Text("Ekle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun ModelsTab(vm: ChatViewModel) {
    val s = vm.settings
    var domain by remember { mutableStateOf(0) }
    val domains = listOf(null, TaskDomain.UI_COMPOSE, TaskDomain.KOTLIN_CORE, TaskDomain.CI_GRADLE_YAML, TaskDomain.GAME_3D_SCRIPT)
    MonoChips(listOf("Tümü", "UI", "Çekirdek", "CI", "3D/Oyun"), domain) { domain = it }
    Text("Mimar (Kilitli): ${s.architectModel}", style = Mono.small, color = Mono2.strong)
    val selected = domains[domain]
    val rows = vm.evoCtg.filter { selected == null || it.domain == selected }
    if (rows.isEmpty()) Text("Bu alanda henüz uygulayıcı ölçümü yok.", style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
    rows.forEach { score -> CtgRow(vm, score) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.evoResetCtg() }, border = BorderStroke(1.dp, Mono2.line)) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp), tint = Mono2.strong)
            Spacer(Modifier.width(6.dp))
            Text("İstatistikleri sıfırla", color = Mono2.strong)
        }
    }
    if (s.pinnedImplementer.isNotBlank()) {
        TextButton(onClick = { vm.evoPinImplementer(s.pinnedImplementer) }) { Text("Sabitlemeyi kaldır", color = Mono2.strong) }
    }
}

@Composable
private fun CtgRow(vm: ChatViewModel, s: CostToGreenScorer.Score) {
    val now = System.currentTimeMillis()
    val cooling = s.cooldownUntil > now
    EvoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.modelId.substringAfter('/'), style = Mono.small, color = Mono2.strong, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cooling) {
                Surface(shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Mono2.line), color = Color.Transparent) {
                    Text("Soğutmada", style = Mono.tiny, color = Mono2.strong, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Row {
            Text("İlk Geçiş %${(s.firstPassCiRate * 100).toInt()}", style = Mono.tiny, color = Mono2.weak, modifier = Modifier.weight(1f))
            Text("CTG $${String.format(java.util.Locale.US, "%.4f", s.costToGreenUsd)}", style = Mono.tiny, color = Mono2.weak, modifier = Modifier.weight(1f))
        }
        Row {
            Text("Salınım %${(s.oscillationRate * 100).toInt()}", style = Mono.tiny, color = Mono2.weak, modifier = Modifier.weight(1f))
            Text("Skor ${String.format(java.util.Locale.US, "%.2f", s.efficiencyScore)}", style = Mono.tiny, color = Mono2.strong, modifier = Modifier.weight(1f))
        }
        TextButton(onClick = { vm.evoPinImplementer(s.modelId) }) {
            Icon(Icons.Outlined.Bookmark, null, Modifier.size(16.dp), tint = Mono2.strong)
            Spacer(Modifier.width(4.dp))
            Text(if (vm.settings.pinnedImplementer == s.modelId) "Sabitlendi" else "Uygulayıcıyı sabitle", color = Mono2.strong)
        }
    }
}

@Composable
private fun MotorTab(vm: ChatViewModel) {
    val s = vm.settings
    val drafts = vm.skills.filter { it.source == com.suarez.coder.skills.Skill.Source.AUTO_SYNTHESIZED && (it.status == com.suarez.coder.skills.Skill.Status.DRAFT || it.status == com.suarez.coder.skills.Skill.Status.REVIEW_REQUIRED) }
    EvoCard {
        CardTitle("Sentezlenen Skill Taslakları")
        if (drafts.isEmpty()) Text("Taslak yok.", style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
        drafts.forEach { skill ->
            Column {
                Text("/${skill.name}", style = Mono.small, color = Mono2.strong)
                Text(skill.description, style = Mono.tiny, color = Mono2.weak, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row {
                    TextButton(onClick = { vm.evoApproveSkill(skill.name) }) { Text("Onayla", color = Mono2.strong) }
                    TextButton(onClick = { vm.deleteSkill(skill.name); vm.refreshEvolution() }) { Text("Sil", color = Mono2.strong) }
                }
            }
        }
    }
    EvoCard {
        CardTitle("Çapraz Repo Genel Kuralları")
        if (vm.evoGeneral.isEmpty()) Text("Terfi eden genel kural yok.", style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
        vm.evoGeneral.forEach { rule ->
            Column {
                Surface(shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Mono2.line), color = Color.Transparent) {
                    Text(rule.techTag, style = Mono.tiny, color = Mono2.strong, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text(rule.text, style = Mono.tiny, color = Mono2.strong, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    EvoCard {
        CardTitle("Strateji A/B ve Gölge Simülasyon")
        Text("Kapalı başlar. Açılınca varyant önce github_list_symbols zorunluluğu uygular. Mimar modeli değişmez.", style = Mono.tiny, color = Mono2.weak)
        MonoSwitch("Uygulayıcı A/B", "Kontrol %70, varyant %30. Oturum başında sabitlenir.", s.abEnabled) { vm.setAbEnabled(it) }
        val ab = vm.evoAb
        if (ab == null) Text("Kayıt yok.", style = Mono.tiny, color = Mono2.weak)
        else {
            Text("Kontrol ${ab.controlN} görev · CTG ${fmt(ab.controlCtg)}", style = Mono.tiny, color = Mono2.strong)
            Text("Varyant ${ab.variantN} görev · CTG ${fmt(ab.variantCtg)}", style = Mono.tiny, color = Mono2.strong)
            if (ab.note.isNotBlank()) Text(ab.note, style = Mono.tiny, color = Mono2.weak)
        }
        Text("Mimar (Kilitli): ${s.architectModel}", style = Mono.tiny, color = Mono2.weak)
        OutlinedButton(onClick = { vm.evoResetAb() }, border = BorderStroke(1.dp, Mono2.line)) { Text("Sıfırla", color = Mono2.strong) }
    }
    EvoCard {
        CardTitle("Proaktif Bekçi")
        MonoSwitch("Bekçi", "Kapalı başlar. main dalına push veya merge yapmaz.", s.sentinelEnabled) { vm.setSentinelEnabled(it) }
        MonoChips(listOf("6 saat", "12 saat", "24 saat"), when (s.sentinelHours) { 6 -> 0; 24 -> 2; else -> 1 }) {
            vm.setSentinelHours(when (it) { 0 -> 6; 2 -> 24; else -> 12 })
        }
        MonoSwitch("Taslak düzeltme hazırla", "İzole sentinel dalında taslak PR. Merge edilmez.", s.sentinelDraft, s.sentinelEnabled) { vm.setSentinelDraft(it) }
        var repos by remember { mutableStateOf(s.sentinelRepos) }
        var budget by remember { mutableStateOf(s.sentinelBudget.toString()) }
        OutlinedTextField(repos, { repos = it; vm.setSentinelRepos(it) }, textStyle = Mono.body, label = { Text("İzlenen repolar (en fazla 5)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(budget, {
            budget = it
            it.replace(',', '.').toDoubleOrNull()?.let { v -> vm.setSentinelBudget(v) }
        }, singleLine = true, textStyle = Mono.body, label = { Text("Günlük bütçe USD") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        if (vm.evoSentinel.isEmpty()) Text("Bekçi raporu yok.", style = Mono.tiny, color = Mono2.weak)
        vm.evoSentinel.takeLast(5).asReversed().forEach { report ->
            Text(report.text, style = Mono.tiny, color = Mono2.strong)
        }
    }
}

private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.4f", v)
