package com.suarez.coder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suarez.coder.data.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Page(val title: String, val icon: String, val blurb: String) {
    HOME("Ayarlar", "", ""),
    PROVIDERS("Sağlayıcılar & API", "🔑", "Anahtarlar, varsayılan modeller, bağlantı testi"),
    ARCHITECT("Mimar & Yönlendirme", "🧭", "Düşünme, çıktı sınırları, Decision 1, Flex"),
    CACHE("Önbellek & Bağlam", "🗄", "Context Caching, sıkıştırma, geçmiş"),
    GITHUB("GitHub & Bütçe", "💳", "Token, repo, onay, bütçe ve fiyatlar"),
    TEMPLATES("Komut şablonları", "⌨", "Sohbette / ile açılan hazır istemler"),
    SKILLS("Yetenekler (Skills)", "", "Skill kataloğu ve bulut araçları (Blender, Python/FFmpeg)"),
    EVOLUTION("Öz-Gelişim", "", "Arka plan öğrenme, yönlendirme ve otomatik PR'lar"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    BackHandler(enabled = page != Page.HOME) { page = Page.HOME }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = { if (page == Page.HOME) onBack() else page = Page.HOME }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                Page.HOME -> HomePage(vm) { page = it }
                Page.PROVIDERS -> ProvidersPage(vm)
                Page.ARCHITECT -> ArchitectPage(vm)
                Page.CACHE -> CachePage(vm)
                Page.GITHUB -> GitHubPage(vm)
                Page.TEMPLATES -> TemplatesPage(vm)
                Page.EVOLUTION -> EvolutionPage(vm)
                Page.SKILLS -> SkillsPage(vm)
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

// ---- Home ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePage(vm: ChatViewModel, open: (Page) -> Unit) {
    val s = vm.settings
    var theme by remember { mutableStateOf(s.themeMode) }
    Group {
        Title("Görünüm", "Koyu: IDE teması. OLED: saf siyah arka plan, AMOLED ekranlarda pil tasarrufu. Sistem: telefonun açık/koyu ayarını izler.")
        Segmented(listOf("dark" to "Koyu", "oled" to "OLED", "light" to "Açık", "system" to "Sistem"), theme) {
            theme = it; s.themeMode = it; vm.refreshSettings()
        }
    }
    val status = listOf(
        "Gemini" to s.geminiKey.isNotBlank(),
        "DeepSeek" to s.deepseekKey.isNotBlank(),
        "OpenRouter" to s.openrouterKey.isNotBlank(),
        "GitHub" to s.githubToken.isNotBlank(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        status.forEach { (name, ok) -> Badge("$name ${if (ok) "✓" else "–"}", if (ok) coder.cacheGood else coder.muted) }
    }
    LaunchedEffect(Unit) { vm.refreshEvolution() }
    listOf(Page.PROVIDERS, Page.ARCHITECT, Page.CACHE, Page.GITHUB, Page.TEMPLATES, Page.SKILLS, Page.EVOLUTION).forEach { p ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, coder.border),
            modifier = Modifier.fillMaxWidth().clickable { open(p) },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p == Page.EVOLUTION) Icon(AutoAwesomeOutlined, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
                else if (p == Page.SKILLS) Icon(ExtensionOutlined, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
                else Text(p.icon, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                    Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = coder.muted)
                }
                if (p == Page.EVOLUTION && vm.evoPending?.state == com.suarez.coder.evolution.PrState.PENDING) {
                    Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = Color.Transparent) {
                        Text("1 PR bekliyor", style = Mono.tiny, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = coder.muted)
            }
        }
    }
}

// ---- 1. Providers & API ----

@Composable
private fun ProvidersPage(vm: ChatViewModel) {
    val s = vm.settings
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var provider by remember { mutableStateOf(s.provider) }
    // The fetched lists are kept in the catalog: no need to press "Modelleri getir" every time.
    fun cached(p: Provider) = s.modelCatalog.filter { it.startsWith(p.name.lowercase() + "/") }.map { it.substringAfter('/') }
    var gModels by remember { mutableStateOf(cached(Provider.GEMINI)) }
    var dModels by remember { mutableStateOf(cached(Provider.DEEPSEEK)) }
    var oModels by remember { mutableStateOf(cached(Provider.OPENROUTER)) }
    var pickFor by remember { mutableStateOf<com.suarez.coder.llm.ModelRef?>(null) }
    @Suppress("UNUSED_VARIABLE") val version = vm.settingsVersion
    var fetchStatus by remember { mutableStateOf("") }
    var modelVersion by remember { mutableIntStateOf(0) }
    var ping by remember { mutableStateOf("") }

    Group {
        Title("Etkin modeller", "Burada seçtiğin model hemen kullanılır (sohbetteki alt çubukla aynı ayar).")
        val options = vm.modelOptions()
        ModelSelect("Mimar", vm.agent.architectRef(), coder.architect, options, { vm.priceLabel(it) }) { vm.selectArchitect(it) }
        ModelSelect("Uygulayıcı", vm.agent.editorRef(), coder.editor, options, { vm.priceLabel(it) }) { vm.selectEditor(it) }
        ModelSelect("Tek model", vm.agent.singleRef(), coder.muted, options, { vm.priceLabel(it) }) { vm.selectSingle(it); provider = it.provider }
        Text(if (s.architectMode) "Mimar Modu açık: Mimar ve Uygulayıcı kullanılır." else "Mimar Modu kapalı: Tek model kullanılır.",
            style = MaterialTheme.typography.bodySmall, color = coder.muted)
    }

    Group {
        Title("Varsayılan yapay zekâ", "Mimar Modu kapalıyken tek model ajanın sağlayıcısı.")
        Segmented(Provider.entries.map { it.name to it.label }, provider.name) {
            provider = Provider.valueOf(it); s.provider = provider; vm.refreshSettings()
        }
    }

    Group {
        Title("Gemini")
        TextSetting("API anahtarı", s.geminiKey, secret = true) { s.geminiKey = it }
        TextButton(onClick = { uri.openUri("https://aistudio.google.com/app/apikey") }) { Text("Anahtar al: aistudio.google.com") }
        key(modelVersion) { TextSetting("Tek model (Mimar Modu kapalıyken)", s.geminiModel, mono = true) { s.geminiModel = it.ifBlank { "gemini-2.5-pro" }; vm.refreshSettings() } }
        ModelFetch(gModels, onPick = { pickFor = com.suarez.coder.llm.ModelRef(Provider.GEMINI, it) }) {
            scope.launch {
                fetchStatus = "Gemini modelleri alınıyor…"
                fetchStatus = runCatching { gModels = vm.fetchModels(Provider.GEMINI); vm.mergeCatalog(Provider.GEMINI, gModels); "${gModels.size} Gemini modeli" }
                    .getOrElse { it.message ?: "Hata" }
            }
        }
    }

    Group {
        Title("DeepSeek", "OpenAI uyumlu herhangi bir API de kullanılabilir: adresi değiştirin.")
        TextSetting("API anahtarı", s.deepseekKey, secret = true) { s.deepseekKey = it }
        TextButton(onClick = { uri.openUri("https://platform.deepseek.com/api_keys") }) { Text("Anahtar al: platform.deepseek.com") }
        key(modelVersion) { TextSetting("Tek model (Mimar Modu kapalıyken)", s.deepseekModel, mono = true) { s.deepseekModel = it.ifBlank { "deepseek-chat" }; vm.refreshSettings() } }
        TextSetting("API adresi", s.deepseekBaseUrl, mono = true) { s.deepseekBaseUrl = it.ifBlank { "https://api.deepseek.com" } }
        ModelFetch(dModels, onPick = { pickFor = com.suarez.coder.llm.ModelRef(Provider.DEEPSEEK, it) }) {
            scope.launch {
                fetchStatus = "DeepSeek modelleri alınıyor…"
                fetchStatus = runCatching { dModels = vm.fetchModels(Provider.DEEPSEEK); vm.mergeCatalog(Provider.DEEPSEEK, dModels); "${dModels.size} DeepSeek modeli" }
                    .getOrElse { it.message ?: "Hata" }
            }
        }
    }
    Group {
        Title("OpenRouter", "Tek anahtarla Claude, GPT, Llama, Qwen ve diğer yüzlerce model. Liste alınınca fiyatlar ve görsel desteği otomatik öğrenilir; " +
            "yalnızca araç çağırabilen modeller listelenir. Maliyet OpenRouter'ın bildirdiği gerçek tutardır; düşünme seviyesi destekleyen modellere gönderilir. " +
            "Geçici hatalar (429, 502, 503…) 3 kez yeniden denenir.")
        TextSetting("API anahtarı", s.openrouterKey, secret = true) { s.openrouterKey = it; vm.refreshSettings() }
        TextButton(onClick = { uri.openUri("https://openrouter.ai/settings/keys") }) { Text("Anahtar al: openrouter.ai") }
        key(modelVersion) {
            TextSetting("Tek model (Mimar Modu kapalıyken)", s.openrouterModel, mono = true, placeholder = "anthropic/claude-sonnet-4.5") {
                s.openrouterModel = it.ifBlank { "deepseek/deepseek-chat" }; vm.refreshSettings()
            }
        }
        ModelFetch(oModels, onPick = { pickFor = com.suarez.coder.llm.ModelRef(Provider.OPENROUTER, it) }) {
            scope.launch {
                fetchStatus = "OpenRouter modelleri alınıyor…"
                fetchStatus = runCatching { oModels = vm.fetchModels(Provider.OPENROUTER); vm.mergeCatalog(Provider.OPENROUTER, oModels); "${oModels.size} OpenRouter modeli (araç destekli)" }
                    .getOrElse { it.message ?: "Hata" }
            }
        }
    }
    if (fetchStatus.isNotBlank()) Text(fetchStatus, style = Mono.tiny, color = coder.muted)
    pickFor?.let { ref ->
        AlertDialog(
            onDismissRequest = { pickFor = null },
            title = { Text(ref.model, style = Mono.body) },
            text = { Text("Bu model nerede kullanılsın? Seçim hemen etkin olur.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.selectArchitect(ref); pickFor = null; modelVersion++ }) { Text("Mimar") }
                    TextButton(onClick = { vm.selectEditor(ref); pickFor = null; modelVersion++ }) { Text("Uygulayıcı") }
                    TextButton(onClick = { vm.selectSingle(ref); provider = ref.provider; pickFor = null; modelVersion++ }) { Text("Tek model") }
                }
            },
            dismissButton = { TextButton(onClick = { pickFor = null }) { Text("Vazgeç") } },
        )
    }

    Group {
        Title("Bağlantı", "Tekil, Mimar ve Uygulayıcı modellerine küçük bir istek gönderir; süreyi veya hatayı gösterir.")
        OutlinedButton(onClick = { scope.launch { ping = "Test ediliyor…"; ping = vm.testConnections() } }) { Text("Bağlantıyı test et") }
        if (ping.isNotBlank()) Text(ping, style = Mono.small)
    }
}

@Composable
private fun ModelFetch(models: List<String>, onPick: (String) -> Unit, onFetch: () -> Unit) {
    OutlinedButton(onClick = onFetch) { Text("Modelleri getir") }
    if (models.isNotEmpty()) {
        var q by remember { mutableStateOf("") }
        if (models.size > 20) OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Model ara (ör. claude, qwen)") },
            textStyle = Mono.small, modifier = Modifier.fillMaxWidth())
        val shown = models.filter { q.isBlank() || it.contains(q.trim(), ignoreCase = true) }.take(40)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { m -> AssistChip(onClick = { onPick(m) }, label = { Text(m, style = Mono.tiny) }) }
        }
    }
}

// ---- 2. Architect & routing ----

@Composable
private fun ArchitectPage(vm: ChatViewModel) {
    val s = vm.settings
    val levels = listOf("minimal", "low", "medium", "high").map { it to it }

    Group {
        Title("Düşünme seviyesi", "Düşünme tokenları çıktı fiyatından ödenir. 'minimal' yalnızca bazı Flash modellerinde vardır; diğerlerinde 'low' kullanılır. Tek model ajanın seviyesi sohbet ekranındaki kontrol çubuğundan seçilir.")
        var arch by remember { mutableStateOf(s.thinkingArchitect) }
        var editor by remember { mutableStateOf(s.thinkingEditor) }
        Label("Mimar", coder.architect)
        Segmented(levels, arch) { arch = it; s.thinkingArchitect = it }
        Label("Uygulayıcı", coder.editor)
        Segmented(levels, editor) { editor = it; s.thinkingEditor = it }
    }

    Group {
        Title("Çıktı sınırları (token)", "Gemini'de düşünme tokenları da dahildir. Kesilen yanıt bir kez iki katıyla yeniden denenir. En fazla 1.048.576; Gemini 4'te uzun yanıtlar parça parça (devam ettirme ile) alınır. Uzun yanıtlar pahalıdır (Carbon'da 100k çıktı ≈ \$1).")
        NumberSetting("Mimar", s.maxOutArchitect) { s.maxOutArchitect = it }
        NumberSetting("Uygulayıcı", s.maxOutEditor) { s.maxOutEditor = it }
        NumberSetting("Tek model ajan", s.maxOutAgent) { s.maxOutAgent = it }
    }

    Group {
        Title("Mimar")
        NumberSetting("Uygulayıcı onarım denemesi", s.maxExecutorRetries,
            info = "Uygulayıcı hatalarını kendi düzeltir (dosyanın güncel halini yeniden okuyarak). Bu kadar denemede olmazsa iş durur ve size sorulur; Mimar otomatik çağrılmaz.") { s.maxExecutorRetries = it }
        SwitchSetting("Repo Haritası", "Ham kod yerine imzalar", s.repoMapForArchitect,
            "Mimar'a dosya yolları, sınıflar ve fonksiyon imzalarından oluşan hafif bir harita gönderilir; en fazla 3 dosya okur.") { s.repoMapForArchitect = it }
        SwitchSetting("Sadece Diff", "Açıklamasız, kısa yanıtlar", s.terseMode,
            "Selamlama, açıklama ve özet yazılmaz; kod yalnızca SEARCH/REPLACE bloklarıyla değişir.") { s.terseMode = it }
        SwitchSetting("Proje notları", ".coder/NOTES.md", s.projectNotes,
            "Repodaki kalıcı proje notları sabit bağlama eklenir; ajan önemli değişikliklerde aynı commit'te günceller.") { s.projectNotes = it }
        SwitchSetting("Ekonomi modu (Flex)", "%50 ucuz, dakikalar sürebilir", s.economyMode,
            "Gemini istekleri Flex kademesinden gider: yarı fiyat, ama yanıtlar 1-15 dakika sürebilir ve kapasite dolabilir. Kapasite dolarsa standart fiyata geçmeden önce sorulur.") { s.economyMode = it; vm.refreshSettings() }
        TextSetting("Orta zorlukta Mimar modeli", s.architectModelMedium, mono = true, placeholder = "boş = kapalı",
            info = "Doluysa: Decision 1 zorluğu 1.5'in altında bulduğunda seçtiğin Mimar yerine bu model kullanılır. Boş (varsayılan) = her zaman seçtiğin Mimar.") { s.architectModelMedium = it; vm.refreshSettings() }
        TextSetting("Yükseltme modeli", s.escalationModel, mono = true, placeholder = "boş = otomatik, none = kapalı",
            info = "Uygulayıcı'nın son iki onarım denemesinde kullanılan ara model (sağlayıcı/model). Mimar hiçbir zaman onarım yapmaz.") { s.escalationModel = it; vm.refreshSettings() }
    }

    Group {
        Title("Orkestratör", "Yalnızca Mimar'a giden (çok dosyalı) işlerde kullanılır. Kapalı: normal akış, ek çağrı yok. " +
            "Hafif Sıralı: plan → alt görevler sırayla, her biri ayrı commit. Tam: plan → dosyaları çakışmayan işçiler paralel → " +
            "İnceleyici → tek commit. Sohbet ekranındaki kontrol panelinden görev başına değiştirilebilir.")
        var orch by remember { mutableStateOf(s.orchestratorMode) }
        Segmented(com.suarez.coder.orchestrator.OrchestratorMode.entries.map { it.name to it.label }, orch) {
            orch = it; s.orchestratorMode = it; vm.refreshSettings()
        }
        NumberSetting("Tam mod: en fazla paralel işçi (1-3)", s.orchMaxParallel,
            info = "Mobil ağ ve hız sınırları için 2 önerilir.") { s.orchMaxParallel = it }
        NumberSetting("Tam mod: en fazla inceleme turu", s.orchMaxReviewRounds,
            info = "İnceleyici'nin açabileceği düzeltme turu; sınırda durup size sorar.") { s.orchMaxReviewRounds = it }
        SwitchSetting("Tam mod: Değerlendirici", "Uygulamayı gerçekten çalıştırıp sınar", s.evaluatorEnabled,
            "Web projelerinde sözleşmedeki (kabul ölçütleri) her maddeyi GitHub Actions'ta Playwright ile tarayıcıda dener; bir madde geçmez " +
                "veya konsolda hata varsa düzeltme turu açar. Android projelerinde CI testleri esas alınır. Daha pahalıdır: test betiği yazma çağrısı + CI dakikaları.") {
            s.evaluatorEnabled = it
        }
    }

    Group {
        Title("Yönlendirme", "Akıllı Yönlendirme açıkken sıra: yerel kural ($0) → Decision 1 → ucuz model → manuel ayar.")
        SwitchSetting("Akıllı Yönlendirme", "Görevi otomatik sınıflandır", s.smartRouting) { s.smartRouting = it; vm.refreshSettings() }
        SwitchSetting("Decision 1 kullan", "Sınıflandırma + dosya seçimi + zorluk", s.useDecision1,
            "Kapalıysa yalnızca yerel kural ve ucuz model kullanılır.") { s.useDecision1 = it }
        var dp by remember { mutableStateOf(s.decisionProvider) }
        Label("Karar sağlayıcısı", coder.muted)
        Segmented(listOf("gemini" to "Google", "openrouter" to "OpenRouter", "auto" to "Yedekli"), dp) { dp = it; s.decisionProvider = it }
        Text(when (dp) {
            "gemini" -> "Yalnızca Gemini Decisions (Gemini anahtarı gerekir)."
            "openrouter" -> "Yalnızca OpenRouter Decisions, ör. Jev (OpenRouter anahtarı gerekir)."
            else -> "Önce Gemini; kota, hata veya zaman aşımında hemen OpenRouter. İkisi de olmazsa ucuz model ve yerel kurallar."
        }, style = MaterialTheme.typography.bodySmall, color = coder.muted)
        TextSetting("Gemini karar modeli", s.decisionModel, mono = true) { s.decisionModel = it.ifBlank { "gemini-decision-1" } }
        TextSetting("OpenRouter karar modeli", s.openrouterDecisionModel, mono = true, placeholder = "typesafe/jev-1.14",
            info = "Decisions API'deki karar modelleri (ör. typesafe/jev-1.14 veya ~typesafe/jev-latest). Sohbet modeli değildir; kod yazmaz.") {
            s.openrouterDecisionModel = it.ifBlank { "typesafe/jev-1.14" }
        }
        TextSetting("Karar zaman aşımı (sn)", (s.decisionTimeoutMs / 1000.0).toString(), keyboard = KeyboardType.Decimal) {
            s.decisionTimeoutMs = ((it.replace(',', '.').toDoubleOrNull() ?: 2.0) * 1000).toLong()
        }
        TextSetting("Ucuz sınıflandırma modeli", s.routerModel, mono = true, placeholder = "boş = otomatik",
            info = "Yerel kural emin olamazsa ve Decision 1 kullanılamazsa tek kelimelik sınıflandırma yapan model (sağlayıcı/model).") { s.routerModel = it }
    }
}

// ---- 3. Cache & context ----

@Composable
private fun CachePage(vm: ChatViewModel) {
    val s = vm.settings
    val scope = rememberCoroutineScope()
    var cacheMsg by remember { mutableStateOf(vm.agent.cacheStatus) }

    Group {
        Title("Context Caching", "Otomatik (implicit) önbellek her zaman çalışır. Bu ayarlar açık (explicit) önbelleği yönetir; Gemini 4 modelleri yalnızca otomatik önbellek destekler.")
        SwitchSetting("Açık önbellek", "Sabit bağlam büyükse önbelleğe al", s.cacheEnabled) { s.cacheEnabled = it }
        SwitchSetting("Ölçüme göre kullan", "Otomatik önbellek tutuyorsa kapalı", s.explicitCacheAuto,
            "Otomatik önbelleğin isabet oranı ölçülür; iyi tutuyorsa açık önbellek (ve saatlik depolama ücreti) kullanılmaz.") { s.explicitCacheAuto = it }
        NumberSetting("Önbellek süresi (dakika)", s.cacheTtlMinutes) { s.cacheTtlMinutes = it }
        NumberSetting("En az sabit bağlam (token)", s.cacheMinTokens,
            info = "Açık önbellek yalnızca sistem talimatı + araçlar + sabit bağlam bu boyutu aşarsa oluşturulur.") { s.cacheMinTokens = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { scope.launch { cacheMsg = runCatching { vm.agent.clearCache() }.getOrElse { it.message ?: "Hata" } } }) {
                Text("🧹 Önbelleği Temizle")
            }
        }
        Text(cacheMsg, style = Mono.tiny, color = coder.muted)
    }

    Group {
        Title("Geçmiş", "Geçmiş eşiğe kadar yalnızca büyür (önbellek tutar); aşınca eski kısım bir kerede kısaltılır ve gerekirse özetlenir.")
        NumberSetting("Toplu sıkıştırma eşiği (karakter)", s.compactTailChars) { s.compactTailChars = it }
        NumberSetting("Tam gönderilecek son tur", s.keepTurns) { s.keepTurns = it }
    }
}

// ---- 4. GitHub & budget ----

@Composable
private fun GitHubPage(vm: ChatViewModel) {
    val s = vm.settings
    val uri = LocalUriHandler.current

    Group {
        Title("GitHub", "Klasik token için 'repo' ve 'workflow' izinleri; ince ayarlı token için Contents, Pull requests ve Actions: Read and write.")
        TextSetting("Token", s.githubToken, secret = true) { s.githubToken = it }
        TextButton(onClick = { uri.openUri("https://github.com/settings/tokens/new?scopes=repo,workflow&description=Coder%20Android") }) {
            Text("Token oluştur: github.com")
        }
        TextSetting("Varsayılan repo", s.defaultRepo, mono = true, placeholder = "sahip/isim") { s.defaultRepo = it; vm.projectSettingsChanged() }
        TextSetting("Varsayılan branch", s.defaultBranch, mono = true, placeholder = "main") { s.defaultBranch = it; vm.projectSettingsChanged() }
        Text("Repo, branch ve özel talimatlar etkin proje profiline kaydedilir. Profiller arasında üst çubuktaki repo rozetine dokunarak geçiş yapılır.",
            style = MaterialTheme.typography.bodySmall, color = coder.muted)
    }

    Group {
        Title("Ajan")
        SwitchSetting("Değişiklikleri otomatik onayla", "Commit, PR, merge için sorma", s.autoApprove) { s.autoApprove = it }
        NumberSetting("Bir istekte en fazla adım", s.maxSteps) { s.maxSteps = it }
        NumberSetting("İstek zaman aşımı (sn)", s.requestTimeoutSec,
            info = "Akış sırasında bu süre boyunca hiç veri gelmezse istek takıldı sayılır (Flex hariç).") { s.requestTimeoutSec = it }
        TextSetting("Özel talimatlar", s.customInstructions, singleLine = false) { s.customInstructions = it; vm.refreshSettings() }
    }

    Group {
        Title("Bütçe ve fiyatlar")
        TextSetting("Oturum bütçe eşiği (USD)", s.budgetUsd.toString(), keyboard = KeyboardType.Decimal, mono = true) {
            s.budgetUsd = it.replace(',', '.').toDoubleOrNull() ?: 1.0
        }
        TextSetting("Dolar kuru (1 USD = ? TL)", s.usdTry.toString(), keyboard = KeyboardType.Decimal, mono = true) {
            it.replace(',', '.').toDoubleOrNull()?.let { v -> s.usdTry = v }
        }
        SwitchSetting("Token önizlemesi", "Gönderim öncesi tahmini maliyet", s.tokenPreview,
            "Gemini'nin ücretsiz countTokens ucuyla hesaplanır.") { s.tokenPreview = it }
        TextSetting("Fiyat düzeltmeleri", s.priceOverrides, singleLine = false, mono = true, placeholder = "gemini-2.5-pro = 1.25, 10, 0.125, 4.5",
            info = "Satır başına: model = giriş, çıkış, önbellekli giriş, saatlik depolama (1M token başına USD). Boşsa yerleşik tablo kullanılır; tabloda olmayan modeller maliyet özetinde * ile işaretlenir.") { s.priceOverrides = it }
    }
}

// ---- 5. Slash templates ----

@Composable
private fun TemplatesPage(vm: ChatViewModel) {
    val s = vm.settings
    var list by remember { mutableStateOf(s.promptTemplates) }
    var version by remember { mutableIntStateOf(0) }
    fun save(new: List<Pair<String, String>>) { list = new; s.promptTemplates = new }

    Text("Sohbette mesaj kutusuna / yazınca bu şablonlar listelenir; seçilen metin kutuya yerleşir.",
        style = MaterialTheme.typography.bodySmall, color = coder.muted)
    list.forEachIndexed { i, (name, text) ->
        key(version, i) {
            Group {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("/", style = Mono.body, color = coder.architect)
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        TextSetting("Ad", name, mono = true) { v ->
                            val clean = v.trim().removePrefix("/").replace(Regex("\\s+"), "-")
                            save(list.toMutableList().also { it[i] = clean to it[i].second })
                        }
                    }
                    TextButton(onClick = { save(list.filterIndexed { j, _ -> j != i }); version++ }) { Text("Sil", color = coder.warn) }
                }
                TextSetting("Metin", text, singleLine = false) { v -> save(list.toMutableList().also { it[i] = it[i].first to v }) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save(list + ("yeni${list.size + 1}" to "")); version++ }) { Text("+ Şablon ekle") }
        TextButton(onClick = { save(com.suarez.coder.data.Settings.DEFAULT_TEMPLATES); version++ }) { Text("Varsayılanlara dön") }
    }
}

// ---- Building blocks ----

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, coder.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Title(text: String, info: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f, fill = false))
        if (info != null) InfoButton(text, info)
    }
}

@Composable
private fun Label(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color)
}

@Composable
private fun InfoButton(title: String, info: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Outlined.Info, "Bilgi", tint = coder.muted, modifier = Modifier.size(18.dp))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(info, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Tamam") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    activeBorderColor = coder.border, inactiveBorderColor = coder.border,
                ),
                icon = {},
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Saved immediately on toggle. */
@Composable
private fun SwitchSetting(title: String, subtitle: String, initial: Boolean, info: String? = null, onChange: (Boolean) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().clickable { value = !value; onChange(value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (info != null) InfoButton(title, info)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = coder.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(value, { value = it; onChange(it) })
    }
}

/**
 * Saved automatically shortly after typing stops, with a brief inline confirmation.
 */
@Composable
private fun TextSetting(
    label: String,
    initial: String,
    secret: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    info: String? = null,
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var saved by remember(initial) { mutableStateOf(initial) }
    var justSaved by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    // Leaving the page (or the field disappearing) within the debounce must not lose what was typed.
    val latestText by rememberUpdatedState(text)
    val latestSaved by rememberUpdatedState(saved)
    val latestOnSave by rememberUpdatedState(onSave)
    DisposableEffect(Unit) { onDispose { if (latestText != latestSaved) latestOnSave(latestText) } }
    LaunchedEffect(text) {
        if (text == saved) return@LaunchedEffect
        delay(700)
        onSave(text)
        saved = text
        justSaved = true
        delay(1500)
        justSaved = false
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, style = Mono.small) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = if (mono) Mono.body else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        trailingIcon = when {
            secret -> { { TextButton(onClick = { show = !show }) { Text(if (show) "Gizle" else "Göster") } } }
            info != null -> { { InfoButton(label, info) } }
            else -> null
        },
        supportingText = if (justSaved) { { Text("✓ Kaydedildi", color = coder.cacheGood, style = MaterialTheme.typography.labelSmall) } } else null,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = coder.border),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberSetting(label: String, initial: Int, info: String? = null, onSave: (Int) -> Unit) {
    TextSetting(label, initial.toString(), mono = true, keyboard = KeyboardType.Number, info = info) { v ->
        v.filter(Char::isDigit).toIntOrNull()?.let(onSave)
    }
}

@Suppress("unused")
@Composable
private fun Divider() = HorizontalDivider(color = coder.border)
