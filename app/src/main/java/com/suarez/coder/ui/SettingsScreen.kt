package com.suarez.coder.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suarez.coder.data.Provider
import com.suarez.coder.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var themeMode by remember { mutableStateOf(s.themeMode) }
    var dynamicColor by remember { mutableStateOf(s.dynamicColor) }
    var gemini by remember { mutableStateOf(s.geminiKey) }
    var deepseek by remember { mutableStateOf(s.deepseekKey) }
    var deepseekBase by remember { mutableStateOf(s.deepseekBaseUrl) }
    var github by remember { mutableStateOf(s.githubToken) }
    var repo by remember { mutableStateOf(s.defaultRepo) }
    var gModel by remember { mutableStateOf(s.geminiModel) }
    var dModel by remember { mutableStateOf(s.deepseekModel) }
    var auto by remember { mutableStateOf(s.autoApprove) }
    var custom by remember { mutableStateOf(s.customInstructions) }
    var steps by remember { mutableStateOf(s.maxSteps.toString()) }
    var provider by remember { mutableStateOf(s.provider) }
    var gModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var dModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf(s.budgetUsd.toString()) }
    var usdTry by remember { mutableStateOf(s.usdTry.toString()) }
    var prices by remember { mutableStateOf(s.priceOverrides) }
    var cacheOn by remember { mutableStateOf(s.cacheEnabled) }
    var cacheTtl by remember { mutableStateOf(s.cacheTtlMinutes.toString()) }
    var keepTurns by remember { mutableStateOf(s.keepTurns.toString()) }
    var previewOn by remember { mutableStateOf(s.tokenPreview) }
    var decisionModel by remember { mutableStateOf(s.decisionModel) }
    var decisionTimeout by remember { mutableStateOf((s.decisionTimeoutMs / 1000.0).toString()) }

    fun rememberFetched(p: Provider, models: List<String>) {
        val prefix = p.name.lowercase() + "/"
        s.modelCatalog = s.modelCatalog.filter { !it.startsWith(prefix) } + models.map { prefix + it }
        if (p == Provider.GEMINI && models.isNotEmpty()) {
            val known = models.toSet()
            if (s.architectModel.startsWith(prefix) && s.architectModel.removePrefix(prefix) !in known) {
                models.firstOrNull { it.contains("pro") }?.let { s.architectModel = prefix + it }
            }
            if (s.editorModel.startsWith(prefix) && s.editorModel.removePrefix(prefix) !in known) {
                (models.firstOrNull { it.contains("flash-lite") } ?: models.firstOrNull { it.contains("flash") })?.let { s.editorModel = prefix + it }
            }
        }
    }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    fun save() {
        s.themeMode = themeMode
        s.dynamicColor = dynamicColor
        s.geminiKey = gemini
        s.deepseekKey = deepseek
        s.deepseekBaseUrl = deepseekBase.ifBlank { "https://api.deepseek.com" }
        s.githubToken = github
        s.defaultRepo = repo
        s.geminiModel = gModel.ifBlank { "gemini-2.5-pro" }
        s.deepseekModel = dModel.ifBlank { "deepseek-chat" }
        s.autoApprove = auto
        s.customInstructions = custom
        s.maxSteps = steps.toIntOrNull() ?: 40
        s.provider = provider
        s.budgetUsd = budget.replace(',', '.').toDoubleOrNull() ?: 1.0
        s.usdTry = usdTry.replace(',', '.').toDoubleOrNull() ?: s.usdTry
        s.priceOverrides = prices
        s.cacheEnabled = cacheOn
        s.cacheTtlMinutes = cacheTtl.toIntOrNull() ?: 10
        s.keepTurns = keepTurns.toIntOrNull() ?: 4
        s.tokenPreview = previewOn
        s.decisionModel = decisionModel.ifBlank { "gemini-decision-1" }
        s.decisionTimeoutMs = ((decisionTimeout.replace(',', '.').toDoubleOrNull() ?: 2.0) * 1000).toLong()
        vm.refreshSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                navigationIcon = {
                    IconButton(onClick = { save(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri")
                    }
                },
                actions = {
                    TextButton(onClick = { save(); onBack() }) {
                        Text("Kaydet", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("Görünüm", Icons.Default.Palette) {
                Text("Tema", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                            onClick = { themeMode = mode },
                            selected = themeMode == mode,
                        ) {
                            Text(mode.label)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dinamik renkler (Material You)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Android 12+ cihazlarda duvar kağıdı renklerini kullanır",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = dynamicColor, onCheckedChange = { dynamicColor = it })
                }
            }

            SettingsSection("Varsayılan Yapay Zekâ", Icons.Default.SmartToy) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    Provider.entries.forEachIndexed { index, p ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = Provider.entries.size),
                            onClick = { provider = p },
                            selected = provider == p,
                        ) {
                            Text(p.label)
                        }
                    }
                }
            }

            SettingsSection("Gemini", Icons.Default.AutoAwesome) {
                SecretField("Gemini API anahtarı", gemini) { gemini = it }
                TextButton(
                    onClick = { uri.openUri("https://aistudio.google.com/app/apikey") },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text("Anahtar al: aistudio.google.com")
                }
                OutlinedTextField(
                    value = gModel,
                    onValueChange = { gModel = it },
                    label = { Text("Gemini modeli") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                ModelPicker(gModels, onPick = { gModel = it }) {
                    scope.launch {
                        status = "Gemini modelleri alınıyor…"
                        s.geminiKey = gemini
                        status = runCatching {
                            gModels = vm.fetchModels(Provider.GEMINI)
                            rememberFetched(Provider.GEMINI, gModels)
                            "${gModels.size} model bulundu"
                        }.getOrElse { it.message ?: "Hata" }
                    }
                }
            }

            SettingsSection("DeepSeek", Icons.Default.Psychology) {
                SecretField("DeepSeek API anahtarı", deepseek) { deepseek = it }
                TextButton(
                    onClick = { uri.openUri("https://platform.deepseek.com/api_keys") },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text("Anahtar al: platform.deepseek.com")
                }
                OutlinedTextField(
                    value = dModel,
                    onValueChange = { dModel = it },
                    label = { Text("DeepSeek modeli") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deepseekBase,
                    onValueChange = { deepseekBase = it },
                    label = { Text("API adresi") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                ModelPicker(dModels, onPick = { dModel = it }) {
                    scope.launch {
                        status = "DeepSeek modelleri alınıyor…"
                        s.deepseekKey = deepseek
                        s.deepseekBaseUrl = deepseekBase
                        status = runCatching {
                            dModels = vm.fetchModels(Provider.DEEPSEEK)
                            rememberFetched(Provider.DEEPSEEK, dModels)
                            "${dModels.size} model bulundu"
                        }.getOrElse { it.message ?: "Hata" }
                    }
                }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsSection("GitHub", Icons.Default.Folder) {
                SecretField("GitHub token (repo + workflow izinli)", github) { github = it }
                TextButton(
                    onClick = { uri.openUri("https://github.com/settings/tokens/new?scopes=repo,workflow&description=Coder%20Android") },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text("Token oluştur: github.com")
                }
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("Varsayılan repo (sahip/isim, isteğe bağlı)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSection("Ajan", Icons.Default.Tune) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Değişiklikleri otomatik onayla", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Kapalıyken commit, branch, PR gibi işlemlerden önce izin sorulur.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
                OutlinedTextField(
                    value = steps,
                    onValueChange = { steps = it.filter(Char::isDigit) },
                    label = { Text("Bir istekte en fazla adım") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Özel talimatlar (isteğe bağlı)") },
                    minLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSection("Maliyet ve Bütçe", Icons.Default.Payments) {
                OutlinedTextField(
                    value = budget,
                    onValueChange = { budget = it },
                    label = { Text("Oturum bütçe eşiği (USD)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = usdTry,
                    onValueChange = { usdTry = it },
                    label = { Text("Dolar kuru (1 USD = ? TL)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gönderim öncesi token önizlemesi", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Gemini'nin ücretsiz countTokens ucuyla hesaplanır.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = previewOn, onCheckedChange = { previewOn = it })
                }
                OutlinedTextField(
                    value = prices,
                    onValueChange = { prices = it },
                    label = { Text("Fiyat düzeltmeleri (isteğe bağlı)") },
                    placeholder = { Text("gemini-2.5-pro = 1.25, 10, 0.125, 4.5") },
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Satır başına: model = giriş, çıkış, önbellekli giriş, saatlik önbellek depolama (1M token başına USD). " +
                        "Boş bırakılırsa yerleşik yaklaşık fiyatlar kullanılır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("Akıllı Yönlendirme (Decision 1)", Icons.Default.Hub) {
                Text(
                    "Her istekte önce Gemini Decisions API görevi sınıflandırır (basit düzenleme / mimari / soru) ve ilgili dosyaları seçer. " +
                        "Hata, kota veya zaman aşımında manuel model ve dosyalarınız kullanılır. Gemini API anahtarı gerekir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = decisionModel,
                    onValueChange = { decisionModel = it },
                    label = { Text("Karar modeli") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = decisionTimeout,
                    onValueChange = { decisionTimeout = it },
                    label = { Text("Zaman aşımı (saniye)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSection("Önbellek ve Geçmiş", Icons.Default.Cached) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gemini Context Caching", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Sistem talimatı, araçlar ve bağlam dosyaları önbelleğe alınır.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = cacheOn, onCheckedChange = { cacheOn = it })
                }
                OutlinedTextField(
                    value = cacheTtl,
                    onValueChange = { cacheTtl = it.filter(Char::isDigit) },
                    label = { Text("Önbellek süresi (dakika)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keepTurns,
                    onValueChange = { keepTurns = it.filter(Char::isDigit) },
                    label = { Text("Tam gönderilecek son tur sayısı") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { save(); onBack() },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Text("Ayarları Kaydet")
            }

            Text(
                "Anahtarlar bu telefonda şifreli olarak saklanır ve yalnızca ilgili servise gönderilir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            content()
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(
                    imageVector = if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (show) "Gizle" else "Göster",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ModelPicker(models: List<String>, onPick: (String) -> Unit, onFetch: () -> Unit) {
    Column {
        OutlinedButton(onClick = onFetch) { Text("Modelleri getir") }
        if (models.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                models.forEach { m -> AssistChip(onClick = { onPick(m) }, label = { Text(m) }) }
            }
        }
    }
}
