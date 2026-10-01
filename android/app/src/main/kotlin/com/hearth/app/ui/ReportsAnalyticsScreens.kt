package com.hearth.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearth.app.ui.theme.HearthColors
import com.hearth.core.Dashboard

@Composable
fun ReportsScreen(s: UiState, vm: HearthViewModel) {
    val report = remember(s.input, s.scope, s.controls.range) { Dashboard.reports(s.input, s.scope, s.controls.range) }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Reports") }
        item { ScopeAndRange(s, vm) }
        item {
            HearthCard {
                Kicker(report.kicker)
                BigNumber(report.totalLabel)
            }
        }
        item { SectionTitle("By category") }
        if (report.categories.isEmpty()) item { EmptyNote("Nothing spent in this period.") }
        items(report.categories) { CategoryBarRow(it, showPct = true) }
        if (report.members.isNotEmpty()) {
            item { SectionTitle("By family member") }
            item {
                HearthCard {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("Member", "Spent", "Top category").forEach {
                            Text(it, color = HearthColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    report.members.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(m.name, color = HearthColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(m.amountLabel, color = HearthColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(m.topCategory, color = HearthColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    val text = buildString {
                        appendLine("${s.familyName} · ${report.kicker}: ${report.totalLabel}")
                        report.categories.forEach { appendLine("${it.name}: ${it.amountLabel} (${it.pctLabel})") }
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share report"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share summary") }
        }
    }
}

@Composable
fun AnalyticsScreen(s: UiState, vm: HearthViewModel) {
    val a = remember(s.input, s.scope) { Dashboard.analytics(s.input, s.scope) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Analytics") }
        item { ScopeAndRange(s, vm, showRange = false) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("LAST ${a.trend.size} MONTHS", 11)
                Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    a.trend.forEach { bar ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight((bar.heightPct / 100f).coerceIn(0.02f, 0.85f))
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(if (bar.current) HearthColors.Accent else HearthColors.Neutral),
                            )
                            Muted(bar.label, 11)
                        }
                    }
                }
            }
        }
        item { SectionTitle("Budgets vs actual") }
        if (a.budgets.isEmpty()) item { EmptyNote("Add a budget in Settings to track it here.") }
        items(a.budgets) { b ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text(b.name, color = HearthColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (b.over) Tag("${b.amountLabel} / ${b.capLabel}", filled = true) else Muted("${b.amountLabel} / ${b.capLabel}")
                }
                Bar(b.pct / 100f, if (b.over) HearthColors.Accent else HearthColors.NeutralLight)
            }
        }
        item { SectionTitle("Top merchants") }
        items(a.topMerchants) { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(m.name, color = HearthColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Muted(m.amountLabel, 14)
            }
        }
        item { SectionTitle("Unusual spending") }
        if (a.alerts.isEmpty()) item { EmptyNote("Nothing unusual this month.") }
        items(a.alerts) { AlertRow(it) }
    }
}
