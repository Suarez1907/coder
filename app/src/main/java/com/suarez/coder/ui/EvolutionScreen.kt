package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
import com.suarez.coder.evolution.EvoTier
import com.suarez.coder.evolution.EvolutionLogEntry
import com.suarez.coder.evolution.PendingSelfPr
import com.suarez.coder.evolution.PrState
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EvolutionPage(vm: ChatViewModel) {
    val s = vm.settings
    LaunchedEffect(Unit) { vm.refreshEvolution() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        vm.evoPending?.let { PendingPrCard(vm, it) }

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
            MonoSwitch("Kendi koduna PR aç (K3)", "Test önce, CI yeşilse PR; merge her zaman sizin onayınızla.", k3, enabled) { k3 = it; s.evoSelfPr = it }
            MonoSwitch("Sadece Wi-Fi'de çalıştır", null, wifi, enabled) { wifi = it; s.evoWifiOnly = it }
            HorizontalDivider(color = Mono2.line)
            var model by remember { mutableStateOf(s.evoModel) }
            val known = remember { vm.agent.openRouterModels() }
            val valid = known.isEmpty() || model.trim().removePrefix("openrouter/") in known
            OutlinedTextField(model, { model = it; s.evoModel = it.ifBlank { "nvidia/nemotron-3-ultra" } }, singleLine = true, textStyle = Mono.body,
                label = { Text("Evrim modeli (OpenRouter)") }, modifier = Modifier.fillMaxWidth(), isError = !valid,
                supportingText = {
                    Text(when {
                        !valid -> "Bu kimlik OpenRouter listesinde yok; aşağıdan seçin."
                        known.isEmpty() -> "Liste yok: Sağlayıcılar > OpenRouter > Modelleri getir ile doğrulanır."
                        else -> "Tüm evrim rolleri yalnızca bu modeli kullanır; sohbet modellerinden bağımsızdır."
                    })
                })
            // Suggestions from the fetched OpenRouter list (Nemotron first), so a changed slug is one tap away.
            val suggestions = remember(known) {
                known.keys.filter { it.contains("nemotron", true) }.sortedByDescending { it.contains("ultra", true) }.take(6)
            }
            if (suggestions.isNotEmpty() && (!valid || suggestions.none { it == model })) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { id ->
                        OutlinedButton(onClick = { model = id; s.evoModel = id }, border = BorderStroke(1.dp, Mono2.line)) {
                            Text(id, style = Mono.tiny, color = Mono2.strong)
                        }
                    }
                }
            }
            var budget by remember { mutableStateOf(s.evoDailyBudget.toString()) }
            OutlinedTextField(budget, { budget = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> s.evoDailyBudget = v } }, singleLine = true,
                textStyle = Mono.body, label = { Text("Günlük evrim bütçesi (USD)") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (s.openrouterKey.isBlank()) Text("OpenRouter anahtarı gerekli (Sağlayıcılar & API).", style = MaterialTheme.typography.bodySmall, color = Mono2.weak)
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
            }
        },
        confirmButton = { TextButton(onClick = { uri.openUri(p.url) }) { Text("GitHub'da aç") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}
