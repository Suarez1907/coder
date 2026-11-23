package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suarez.coder.evolution.Experiment

private val ink @Composable get() = MaterialTheme.colorScheme.onSurface
private val dim @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val rule @Composable get() = MaterialTheme.colorScheme.outlineVariant

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, rule), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ink)

/** Thumbs under the latest answer: the clearest signal for the learned model choice and the exams. */
@Composable
fun RateRow(vm: ChatViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Text("Bu cevap", style = Mono.tiny, color = dim)
        IconButton(onClick = { vm.rate(1) }) {
            Icon(Icons.Outlined.ThumbUp, "İyi", tint = if (vm.lastRating > 0) ink else dim, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { vm.rate(-1) }) {
            Icon(Icons.Outlined.ThumbUp, "Kötü", tint = if (vm.lastRating < 0) ink else dim, modifier = Modifier.size(18.dp).graphicsLayer(rotationZ = 180f))
        }
        if (vm.lastRating != 0) Text("kaydedildi, öğrenmede kullanılacak", style = Mono.tiny, color = dim)
    }
}

/** Crash notice, weekly report, the manager (goal, autonomy, quality/cost, model pool) and the experiments. */
@Composable
fun ManagerSection(vm: ChatViewModel) {
    val s = vm.settings
    LaunchedEffect(Unit) { vm.refreshManager() }

    vm.crashNotice?.let { msg ->
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, tint = ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = { vm.rollbackLastMerge() }, enabled = !vm.evoBusy, border = BorderStroke(1.dp, rule)) { Text("Son güncellemeyi geri al", color = ink) }
        }
    }

    vm.weekly?.let { r ->
        Card {
            Heading("Haftalık rapor (son 7 gün)")
            r.lines().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = ink) }
        }
    }

    Card {
        Heading("Yönetici")
        Text("Hedefini kendi cümlelerinle yaz; yönetici bunu sınırlı ayarlara çevirir ve uygulamadan önce gösterir.",
            style = MaterialTheme.typography.bodySmall, color = dim)
        var goal by remember { mutableStateOf(s.managerGoal) }
        OutlinedTextField(goal, { goal = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("ör. Bu ay en fazla 5 dolar harca, kaliteyi düşürmeden ucuzlat") })
        OutlinedButton(onClick = { vm.planGoal(goal) }, enabled = goal.isNotBlank() && !vm.evoBusy, border = BorderStroke(1.dp, rule)) { Text("Hedefi yorumla", color = ink) }
        vm.goalPlan?.let { plan ->
            Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, rule), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plan.summary, style = MaterialTheme.typography.bodyMedium, color = ink)
                    val changes = plan.changes(s)
                    if (changes.isEmpty()) Text("Değişiklik yok.", style = MaterialTheme.typography.bodySmall, color = dim)
                    changes.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall, color = ink) }
                    Row {
                        TextButton(onClick = { vm.applyGoal() }, enabled = changes.isNotEmpty()) { Text("Uygula", color = ink) }
                        TextButton(onClick = { vm.dismissGoal() }) { Text("Vazgeç", color = dim) }
                    }
                }
            }
        }
        HorizontalDivider(color = rule)

        Text("Yetki seviyesi", style = MaterialTheme.typography.labelLarge, color = dim)
        var level by remember { mutableStateOf(s.autonomyLevel) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(1, 2, 3).forEachIndexed { i, l ->
                SegmentedButton(selected = level == l, onClick = { level = l; s.autonomyLevel = l; vm.refreshSettings() },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.surfaceVariant, activeContentColor = ink,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface, inactiveContentColor = dim, activeBorderColor = rule, inactiveBorderColor = rule),
                ) { Text("$l") }
            }
        }
        Text(when (level) {
            1 -> "1: Önerir, sen onaylarsın."
            2 -> "2: Sınavı kazanan model, kural ve yetenek değişikliklerini kendisi uygular (günlükten geri alınabilir)."
            else -> "3: Ayrıca kendi koduna PR'ları kurallar sağlanırsa kendisi merge eder (CI yeşil, küçük, korumalı dosya yok, en az 2 sorunsuz geçmiş merge); güncellemeden sonra çökme artarsa kendisi geri alır."
        }, style = MaterialTheme.typography.bodySmall, color = dim)

        Text("Kalite ↔ maliyet", style = MaterialTheme.typography.labelLarge, color = dim)
        var cw by remember { mutableFloatStateOf(s.costWeight.toFloat()) }
        Slider(cw, { cw = it }, onValueChangeFinished = { s.costWeight = cw.toDouble() }, valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = ink, activeTrackColor = ink, inactiveTrackColor = rule))
        Row { Text("En iyi sonuç", style = Mono.tiny, color = dim, modifier = Modifier.weight(1f)); Text("En ucuz", style = Mono.tiny, color = dim) }

        var learned by remember { mutableStateOf(s.learnedRouting) }
        ManagerSwitch("Öğrenen model seçimi", "Havuzdan, iş türüne göre başarı/maliyet geçmişiyle seçer; arada alternatif dener.", learned) { learned = it; s.learnedRouting = it }
        PoolRow(vm)
        var scout by remember { mutableStateOf(s.scoutEnabled) }
        ManagerSwitch("GitHub keşfi", "Haftada bir yeni yetenek arar; bulduklarını yalnızca sınavı kazanırsa açar.", scout) { scout = it; s.scoutEnabled = it }
        var budget by remember { mutableStateOf(s.examWeeklyBudget.toString()) }
        OutlinedTextField(budget, { budget = it; it.replace(',', '.').toDoubleOrNull()?.let { v -> s.examWeeklyBudget = v } }, singleLine = true,
            textStyle = Mono.body, label = { Text("Haftalık sınav bütçesi (USD)") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedButton(onClick = { vm.runWeeklyNow() }, enabled = !vm.evoBusy, border = BorderStroke(1.dp, rule)) {
            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp), tint = ink); Spacer(Modifier.width(6.dp))
            Text("Haftalık döngüyü şimdi çalıştır", color = ink)
        }
        if (!s.evoEnabled) Text("Not: arka plan döngüsü için yukarıda öz-gelişimi açın; elle çalıştırma her zaman olur.",
            style = MaterialTheme.typography.bodySmall, color = dim)
    }

    if (vm.experiments.isNotEmpty()) Card {
        Heading("Deneyler")
        Text("Her değişiklik geçmişteki başarılı işlerinden oluşan sınavda mevcut halle karşılaştırılır; yalnızca kazanan kalır.",
            style = MaterialTheme.typography.bodySmall, color = dim)
        vm.experiments.take(20).forEach { e -> ExperimentRow(vm, e) }
    }
}

@Composable
private fun ManagerSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = dim)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.surface, checkedTrackColor = ink,
            uncheckedThumbColor = dim, uncheckedTrackColor = MaterialTheme.colorScheme.surface, uncheckedBorderColor = rule))
    }
}

@Composable
private fun PoolRow(vm: ChatViewModel) {
    var open by remember { mutableStateOf(false) }
    val pool = vm.settings.modelPool
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 4.dp)) {
        Text("Model havuzu (${pool.size})", style = MaterialTheme.typography.bodyLarge, color = ink)
        Text(if (pool.size < 2) "En az 2 model seçin; yoksa senin sabit seçimin kullanılır." else pool.joinToString { it.substringAfter('/') },
            style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (open) {
        val options = vm.modelOptions().map { it.key }
        var picked by remember { mutableStateOf(pool.toSet()) }
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Model havuzu") },
            text = {
                Column {
                    Text("Yönetici yalnızca bunlardan seçer. Pahalı ve ucuz modelleri birlikte koyun.", style = MaterialTheme.typography.bodySmall, color = dim)
                    if (options.size > 12) OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Ara") }, textStyle = Mono.small, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(options.filter { q.isBlank() || it.contains(q, true) }, key = { it }) { m ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { picked = if (m in picked) picked - m else picked + m }) {
                                Checkbox(m in picked, { on -> picked = if (on) picked + m else picked - m },
                                    colors = CheckboxDefaults.colors(checkedColor = ink, checkmarkColor = MaterialTheme.colorScheme.surface, uncheckedColor = dim))
                                Column {
                                    Text(m.substringAfter('/'), style = Mono.small, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    com.suarez.coder.llm.ModelRef.parse(m)?.let { Text(vm.priceLabel(it), style = Mono.tiny, color = dim) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.settings.modelPool = picked.toList(); vm.refreshSettings(); open = false }) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun ExperimentRow(vm: ChatViewModel, e: Experiment) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, rule), color = MaterialTheme.colorScheme.surface) {
                Text(e.state.label, style = Mono.tiny, color = ink, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(e.title, style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val detail = listOfNotNull(e.source.ifBlank { null }, e.baseline?.let { "mevcut $it" }, e.candidate?.let { "aday $it" }, e.note).joinToString(" · ")
        if (detail.isNotBlank()) Text(detail, style = Mono.tiny, color = dim)
        if (e.state == Experiment.State.PROPOSED) Row {
            TextButton(onClick = { vm.decideExperiment(e, true) }) { Text("Uygula", color = ink) }
            TextButton(onClick = { vm.decideExperiment(e, false) }) { Text("Reddet", color = dim) }
        }
        Spacer(Modifier.size(4.dp))
        HorizontalDivider(color = rule)
    }
}
