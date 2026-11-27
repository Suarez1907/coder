package com.suarez.coder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.suarez.coder.cost.FinOpsReport
import com.suarez.coder.cost.Pricing

/** Monochrome savings card on the cost dashboard. No emoji; monospace tabular figures. */
@Composable
fun FinOpsSavingsCard(summary: FinOpsReport.Summary) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, coder.border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("FinOps Tasarrufları", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        RowLine(Icons.Outlined.Analytics, "Turn-0 Paketleyici",
            "${summary.turn0Runs} görev · ${summary.turn0SavedTurns} tur · ${Pricing.formatTokens(summary.turn0SavedTokens)}", money = false)
        RowLine(Icons.Outlined.PauseCircle, "Akış Devre Kesicisi",
            "${summary.watchdogCuts} kesinti · ${Pricing.formatUsd(summary.watchdogSavedUsd)}", money = true)
        RowLine(Icons.Outlined.DriveFileRenameOutline, "Sıfır-Token Code-Mod",
            "${summary.codeModFiles} dosya", money = false)
        RowLine(Icons.Outlined.Layers, "Flex/Önbellek", Pricing.formatUsd(summary.flexSavedUsd), money = true)
    }
}

@Composable
private fun RowLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, money: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = coder.muted, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, style = Mono.small.copy(fontFeatureSettings = "tnum"), color = if (money) coder.cost else coder.muted, maxLines = 1)
    }
}
