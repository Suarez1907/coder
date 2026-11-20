package com.suarez.coder.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.suarez.coder.data.Provider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val s = vm.settings
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
    var terse by remember { mutableStateOf(s.terseMode) }
    var outArch by remember { mutableStateOf(s.maxOutArchitect.toString()) }
    var outEditor by remember { mutableStateOf(s.maxOutEditor.toString()) }
    var outAgent by remember { mutableStateOf(s.maxOutAgent.toString()) }
    var cacheMin by remember { mutableStateOf(s.cacheMinTokens.toString()) }
    var repoMap by remember { mutableStateOf(s.repoMapForArchitect) }
    var routerModel by remember { mutableStateOf(s.routerModel) }
    var useDecision by remember { mutableStateOf(s.useDecision1) }
    var cacheMsg by remember { mutableStateOf(vm.agent.cacheStatus) }
    var thinkArch by remember { mutableStateOf(s.thinkingArchitect) }
    var thinkEditor by remember { mutableStateOf(s.thinkingEditor) }
    var compactTail by remember { mutableStateOf(s.compactTailChars.toString()) }
    var explicitAuto by remember { mutableStateOf(s.explicitCacheAuto) }
    var notes by remember { mutableStateOf(s.projectNotes) }
    var escalation by remember { mutableStateOf(s.escalationModel) }
    var economy by remember { mutableStateOf(s.economyMode) }
    var timeoutSec by remember { mutableStateOf(s.requestTimeoutSec.toString()) }
    var pingResult by remember { mutableStateOf("") }
    var decisionTimeout by remember { mutableStateOf((s.decisionTimeoutMs / 1000.0).toString()) }

    fun remember(p: Provider, models: List<String>) = vm.mergeCatalog(p, models)
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    fun save() {
        s.geminiKey = gemini; s.deepseekKey = deepseek; s.deepseekBaseUrl = deepseekBase.ifBlank { "https://api.deepseek.com" }
        s.githubToken = github; s.defaultRepo = repo
        s.geminiModel = gModel.ifBlank { "gemini-2.5-pro" }; s.deepseekModel = dModel.ifBlank { "deepseek-chat" }
        s.autoApprove = auto; s.customInstructions = custom; s.maxSteps = steps.toIntOrNull() ?: 40
        s.provider = provider
        s.budgetUsd = budget.replace(',', '.').toDoubleOrNull() ?: 1.0
        s.usdTry = usdTry.replace(',', '.').toDoubleOrNull() ?: s.usdTry
        s.priceOverrides = prices
        s.cacheEnabled = cacheOn
        s.cacheTtlMinutes = cacheTtl.toIntOrNull() ?: 10
        s.keepTurns = keepTurns.toIntOrNull() ?: 4
        s.tokenPreview = previewOn
        s.decisionModel = decisionModel.ifBlank { "gemini-decision-1" }
        s.terseMode = terse
        s.maxOutArchitect = outArch.toIntOrNull() ?: 1500
        s.maxOutEditor = outEditor.toIntOrNull() ?: 4096
        s.maxOutAgent = outAgent.toIntOrNull() ?: 4096
        s.cacheMinTokens = cacheMin.toIntOrNull() ?: 32_000
        s.repoMapForArchitect = repoMap
        s.routerModel = routerModel
        s.useDecision1 = useDecision
        s.thinkingArchitect = thinkArch
        s.thinkingEditor = thinkEditor
        s.compactTailChars = compactTail.toIntOrNull() ?: 80_000
        s.explicitCacheAuto = explicitAuto
        s.projectNotes = notes
        s.escalationModel = escalation
        s.economyMode = economy
        s.requestTimeoutSec = timeoutSec.toIntOrNull() ?: 180
        s.decisionTimeoutMs = ((decisionTimeout.replace(',', '.').toDoubleOrNull() ?: 2.0) * 1000).toLong()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Ayarlar") },
            navigationIcon = { IconButton(onClick = { save(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
            actions = { TextButton(onClick = { save(); onBack() }) { Text("Kaydet") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Varsayılan yapay zekâ", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.entries.forEach { p ->
                    if (p == provider) Button(onClick = {}) { Text(p.label) }
                    else OutlinedButton(onClick = { provider = p }) { Text(p.label) }
                }
            }

            HorizontalDivider()
            Text("Gemini", style = MaterialTheme.typography.titleSmall)
            SecretField("Gemini API anahtarı", gemini) { gemini = it }
            TextButton(onClick = { uri.openUri("https://aistudio.google.com/app/apikey") }) { Text("Anahtar al: aistudio.google.com") }
            OutlinedTextField(gModel, { gModel = it }, label = { Text("Gemini modeli") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ModelPicker(gModels, onPick = { gModel = it }) {
                scope.launch {
                    status = "Gemini modelleri alınıyor…"; s.geminiKey = gemini
                    status = runCatching { gModels = vm.fetchModels(Provider.GEMINI); remember(Provider.GEMINI, gModels); "${gModels.size} model bulundu" }
                        .getOrElse { it.message ?: "Hata" }
                }
            }

            HorizontalDivider()
            Text("DeepSeek", style = MaterialTheme.typography.titleSmall)
            SecretField("DeepSeek API anahtarı", deepseek) { deepseek = it }
            TextButton(onClick = { uri.openUri("https://platform.deepseek.com/api_keys") }) { Text("Anahtar al: platform.deepseek.com") }
            OutlinedTextField(dModel, { dModel = it }, label = { Text("DeepSeek modeli") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(deepseekBase, { deepseekBase = it }, label = { Text("API adresi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ModelPicker(dModels, onPick = { dModel = it }) {
                scope.launch {
                    status = "DeepSeek modelleri alınıyor…"; s.deepseekKey = deepseek; s.deepseekBaseUrl = deepseekBase
                    status = runCatching { dModels = vm.fetchModels(Provider.DEEPSEEK); remember(Provider.DEEPSEEK, dModels); "${dModels.size} model bulundu" }
                        .getOrElse { it.message ?: "Hata" }
                }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("GitHub", style = MaterialTheme.typography.titleSmall)
            SecretField("GitHub token (repo + workflow izinli)", github) { github = it }
            TextButton(onClick = { uri.openUri("https://github.com/settings/tokens/new?scopes=repo,workflow&description=Coder%20Android") }) {
                Text("Token oluştur: github.com")
            }
            OutlinedTextField(repo, { repo = it }, label = { Text("Varsayılan repo (sahip/isim, isteğe bağlı)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Ajan", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Değişiklikleri otomatik onayla")
                    Text("Kapalıyken commit, branch, PR gibi işlemlerden önce izin sorulur.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(auto, { auto = it })
            }
            OutlinedTextField(steps, { steps = it.filter(Char::isDigit) }, label = { Text("Bir istekte en fazla adım") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(custom, { custom = it }, label = { Text("Özel talimatlar (isteğe bağlı)") }, minLines = 3, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Maliyet ve bütçe", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(budget, { budget = it }, label = { Text("Oturum bütçe eşiği (USD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(usdTry, { usdTry = it }, label = { Text("Dolar kuru (1 USD = ? TL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gönderim öncesi token önizlemesi")
                    Text("Gemini'nin ücretsiz countTokens ucuyla hesaplanır.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(previewOn, { previewOn = it })
            }
            OutlinedTextField(
                prices, { prices = it },
                label = { Text("Fiyat düzeltmeleri (isteğe bağlı)") },
                placeholder = { Text("gemini-2.5-pro = 1.25, 10, 0.125, 4.5") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Satır başına: model = giriş, çıkış, önbellekli giriş, saatlik önbellek depolama (1M token başına USD). " +
                    "Boş bırakılırsa yerleşik yaklaşık fiyatlar kullanılır; güncel fiyatları Google/DeepSeek fiyat sayfasından kontrol edin.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("Bağlantı", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = {
                save()
                scope.launch { pingResult = "Test ediliyor…"; pingResult = vm.testConnections() }
            }) { Text("Bağlantıyı test et") }
            if (pingResult.isNotBlank()) Text(pingResult, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(timeoutSec, { timeoutSec = it.filter(Char::isDigit) }, label = { Text("İstek zaman aşımı (saniye, Flex hariç)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Çıktı tasarrufu", style = MaterialTheme.typography.titleSmall)
            ToggleRow("Sadece Diff (Açıklamasız Mod)", "Selamlama, açıklama ve özet yazılmaz; kod yalnızca SEARCH/REPLACE bloklarıyla değişir.", terse) { terse = it }
            OutlinedTextField(outArch, { outArch = it.filter(Char::isDigit) }, label = { Text("Mimar planı için en fazla çıktı tokenı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(outEditor, { outEditor = it.filter(Char::isDigit) }, label = { Text("Uygulayıcı için en fazla çıktı tokenı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(outAgent, { outAgent = it.filter(Char::isDigit) }, label = { Text("Tek model ajan için en fazla çıktı tokenı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Gemini'de düşünme tokenları da bu sınıra dahildir. Yanıt kesilirse bir kez iki katı sınırla yeniden denenir.", style = MaterialTheme.typography.bodySmall)
            Text("Role göre düşünme seviyesi (düşünme tokenları çıktı fiyatından ödenir)", style = MaterialTheme.typography.bodySmall)
            LevelPicker("Mimar", thinkArch) { thinkArch = it }
            LevelPicker("Uygulayıcı", thinkEditor) { thinkEditor = it }
            Text("Tek model ajanın seviyesi sohbet ekranındaki 'Düşünme' düğmesinden seçilir. 'minimal' yalnızca bazı Flash modellerinde vardır; diğerlerinde 'low' kullanılır.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(escalation, { escalation = it }, label = { Text("Yükseltme modeli (sağlayıcı/model; boş = otomatik, none = kapalı)") },
                placeholder = { Text("gemini/gemini-3.8-flash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ToggleRow("Ekonomi modu (Flex, %50)", "Gemini istekleri Flex kademesinden gider: yarı fiyat, ama yanıtlar dakikalar sürebilir ve kapasite dolabilir.", economy) { economy = it }
            ToggleRow("Proje notları (.coder/NOTES.md)", "Repodaki kalıcı proje notları sabit bağlama eklenir; ajan önemli değişikliklerde günceller.", notes) { notes = it }
            ToggleRow("Mimar için Repo Haritası kullan", "Mimar'a ham kod yerine dosya yolları, sınıflar ve fonksiyon imzalarından oluşan hafif bir harita gönderilir.", repoMap) { repoMap = it }

            HorizontalDivider()
            Text("Yönlendirme yedeği", style = MaterialTheme.typography.titleSmall)
            Text("Akıllı Yönlendirme açıkken önce ücretsiz yerel kurallar, sonra Decision 1, sonra aşağıdaki ucuz model denenir.", style = MaterialTheme.typography.bodySmall)
            ToggleRow("Decision 1 kullan", "Kapalıysa yalnızca yerel kural ve ucuz model kullanılır.", useDecision) { useDecision = it }
            OutlinedTextField(routerModel, { routerModel = it }, label = { Text("Ucuz sınıflandırma modeli (sağlayıcı/model, boşsa otomatik)") },
                placeholder = { Text("gemini/gemini-2.5-flash-lite") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Akıllı Yönlendirme (Decision 1)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Her istekte önce Gemini Decisions API görevi sınıflandırır (basit düzenleme / mimari / soru) ve ilgili dosyaları seçer. " +
                    "Hata, kota veya zaman aşımında manuel model ve dosyalarınız kullanılır. Gemini API anahtarı gerekir.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(decisionModel, { decisionModel = it }, label = { Text("Karar modeli") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(decisionTimeout, { decisionTimeout = it }, label = { Text("Zaman aşımı (saniye)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            Text("Önbellek ve geçmiş", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gemini Context Caching")
                    Text("Sistem talimatı, araçlar ve bağlam dosyaları önbelleğe alınır (yeterince büyükse).", style = MaterialTheme.typography.bodySmall)
                }
                Switch(cacheOn, { cacheOn = it })
            }
            OutlinedTextField(cacheTtl, { cacheTtl = it.filter(Char::isDigit) }, label = { Text("Önbellek süresi (dakika)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cacheMin, { cacheMin = it.filter(Char::isDigit) }, label = { Text("Önbellek için en az sabit bağlam (token)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ToggleRow("Açık önbelleği ölçüme göre kullan", "Otomatik önbellek iyi tutuyorsa açık önbellek (ve depolama ücreti) kullanılmaz.", explicitAuto) { explicitAuto = it }
            OutlinedTextField(compactTail, { compactTail = it.filter(Char::isDigit) }, label = { Text("Toplu sıkıştırma eşiği (karakter)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Geçmiş bu boyuta ulaşana kadar yalnızca büyür (önbellek tutar); aşınca eski kısım bir kerede kısaltılır.", style = MaterialTheme.typography.bodySmall)
            Text("Gemini 4 modelleri yalnızca otomatik önbellek destekler; onlarda açık önbellek oluşturulmaz.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { scope.launch { cacheMsg = runCatching { vm.agent.clearCache() }.getOrElse { it.message ?: "Hata" } } }) {
                    Text("Önbelleği Temizle")
                }
                Spacer(Modifier.size(8.dp))
                Text(cacheMsg, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(keepTurns, { keepTurns = it.filter(Char::isDigit) }, label = { Text("Tam gönderilecek son tur sayısı") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.size(8.dp))
            Button(onClick = { save(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Kaydet") }
            Text("Anahtarlar bu telefonda şifreli olarak saklanır ve yalnızca ilgili servise gönderilir.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LevelPicker(label: String, value: String, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text("$label:", style = MaterialTheme.typography.bodyMedium)
        listOf("minimal", "low", "medium", "high").forEach { lv ->
            if (lv == value) Button(onClick = {}) { Text(lv) } else OutlinedButton(onClick = { onPick(lv) }) { Text(lv) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
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
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Gizle" else "Göster") } },
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
