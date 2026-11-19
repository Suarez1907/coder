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
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    fun save() {
        s.geminiKey = gemini; s.deepseekKey = deepseek; s.deepseekBaseUrl = deepseekBase.ifBlank { "https://api.deepseek.com" }
        s.githubToken = github; s.defaultRepo = repo
        s.geminiModel = gModel.ifBlank { "gemini-2.5-pro" }; s.deepseekModel = dModel.ifBlank { "deepseek-chat" }
        s.autoApprove = auto; s.customInstructions = custom; s.maxSteps = steps.toIntOrNull() ?: 40
        s.provider = provider
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
                    status = runCatching { gModels = vm.fetchModels(Provider.GEMINI); "${gModels.size} model bulundu" }
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
                    status = runCatching { dModels = vm.fetchModels(Provider.DEEPSEEK); "${dModels.size} model bulundu" }
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

            Spacer(Modifier.size(8.dp))
            Button(onClick = { save(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Kaydet") }
            Text("Anahtarlar bu telefonda şifreli olarak saklanır ve yalnızca ilgili servise gönderilir.", style = MaterialTheme.typography.bodySmall)
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
