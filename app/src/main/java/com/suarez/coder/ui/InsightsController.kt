package com.suarez.coder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.suarez.coder.agent.Agent
import com.suarez.coder.cost.Ledger
import com.suarez.coder.data.Settings
import com.suarez.coder.evolution.EvolutionStore
import com.suarez.coder.llm.ModelRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Model report card, cache hit rates and price watch (the user's own data). */
class InsightsController(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val evoStore: EvolutionStore,
    private val ledger: Ledger,
    private val agent: () -> Agent,
    private val modelOptions: () -> List<ModelRef>,
    private val refreshSettings: () -> Unit,
) {
    var modelRows by mutableStateOf<List<com.suarez.coder.cost.ModelReport.Row>>(emptyList())
        private set
    var cacheRows by mutableStateOf<List<com.suarez.coder.cost.ModelReport.CacheRow>>(emptyList())
        private set
    /** Escalation order suggested by the user's own results (provider/model keys). */
    var suggestedChain by mutableStateOf<List<String>>(emptyList())
        private set
    var priceAlerts by mutableStateOf<List<String>>(emptyList())
        private set

    private fun keyFor(modelName: String): String? =
        modelOptions().firstOrNull { it.model == modelName || it.key == modelName }?.key

    fun refreshReport() {
        scope.launch(Dispatchers.IO) {
            val since = System.currentTimeMillis() - 60L * 86_400_000L
            val rows = com.suarez.coder.cost.ModelReport.rows(evoStore.traces(), since)
            val cache = com.suarez.coder.cost.ModelReport.cacheRows(ledger.readAll(), System.currentTimeMillis() - 30L * 86_400_000L)
            val chain = com.suarez.coder.cost.ModelReport.suggestedChain(rows, agent().editorRef().model)
            withContext(Dispatchers.Main) { modelRows = rows; cacheRows = cache; suggestedChain = chain.mapNotNull { keyFor(it) } }
        }
    }

    fun applySuggestedChain() {
        if (suggestedChain.isEmpty()) return
        settings.escalationChain = suggestedChain.joinToString("\n")
        refreshSettings()
    }

    /** Compares the prices of the models in use with the last check; also warns about announced price changes. */
    fun checkPrices() {
        scope.launch(Dispatchers.Default) {
            val watched = (listOf(agent().editorRef(), agent().architectRef(), agent().singleRef()).map { it.model } +
                com.suarez.coder.agent.Escalation.parse(settings.escalationChain).map { it.substringAfter('/') } +
                settings.modelPool.map { it.substringAfter('/') }).filter { it.isNotBlank() }.distinct()
            val now = watched.associateWith { agent().price(it) }
            val old = com.suarez.coder.cost.PriceWatch.parse(settings.priceSnapshot)
            val fresh = com.suarez.coder.cost.PriceWatch.alerts(old, now).map { a ->
                // A cheaper model that did as well in the user's own tasks, if the report knows one.
                val me = modelRows.firstOrNull { it.model == a.model }
                val alt = me?.let { m -> modelRows.firstOrNull { it.model != m.model && it.tasks >= com.suarez.coder.cost.ModelReport.MIN_TASKS &&
                    it.avgCost < m.avgCost && it.successRate >= m.successRate - 0.05 } }
                a.text + (alt?.let { " Kendi görevlerinde benzer başarılı ve daha ucuz: ${it.model}." } ?: "")
            }
            settings.priceSnapshot = com.suarez.coder.cost.PriceWatch.snapshot(now)
            val active = (settings.activePriceAlerts + fresh) - settings.dismissedPriceAlerts
            settings.activePriceAlerts = active
            withContext(Dispatchers.Main) { priceAlerts = active.toList() }
        }
    }

    fun dismissPriceAlert(text: String) {
        settings.dismissedPriceAlerts = settings.dismissedPriceAlerts + text
        settings.activePriceAlerts = settings.activePriceAlerts - text
        priceAlerts = priceAlerts - text
    }
}
