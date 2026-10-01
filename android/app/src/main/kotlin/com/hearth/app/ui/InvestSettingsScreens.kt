package com.hearth.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearth.app.ui.theme.HearthColors
import com.hearth.core.Dashboard

private val ALLOCATION_COLORS =
    listOf(HearthColors.Accent, HearthColors.AccentDeep, HearthColors.NeutralLight, HearthColors.Accent2, HearthColors.Neutral, HearthColors.Muted)

@Composable
fun InvestScreen(s: UiState, vm: HearthViewModel) {
    val p = remember(s.input) { Dashboard.portfolio(s.input) }
    var dialog by remember { mutableStateOf<Form?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Family investments") }
        item {
            HearthCard {
                Kicker("Total portfolio value")
                BigNumber(p.totalLabel)
                Muted("▲ ${p.gainLabel} overall", 12)
            }
        }
        item { SectionTitle("By member") { LinkText("+ Holding") { dialog = Form.HOLDING } } }
        if (p.members.isEmpty()) item { EmptyNote("Add your family's holdings — mutual funds, stocks, FDs, gold.") }
        items(p.members) { m ->
            HearthCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = HearthColors.Text, fontSize = 14.sp)
                        Text(m.valueLabel, color = HearthColors.Text, fontSize = 18.sp)
                    }
                    Tag("▲ ${m.gainLabel}", filled = true)
                }
            }
        }
        if (p.allocation.isNotEmpty()) {
            item { SectionTitle("Asset allocation") }
            item {
                Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                    p.allocation.forEachIndexed { i, a ->
                        Box(Modifier.weight(a.pct.coerceAtLeast(1).toFloat()).fillMaxHeight().background(ALLOCATION_COLORS[i % ALLOCATION_COLORS.size]))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    p.allocation.forEachIndexed { i, a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(ALLOCATION_COLORS[i % ALLOCATION_COLORS.size]))
                            Spacer(Modifier.width(8.dp))
                            Muted("${a.name} ${a.pct}%", 13)
                        }
                    }
                }
            }
        }
        item {
            HearthCard {
                Kicker("Net worth")
                Text(p.netWorthLabel, color = HearthColors.Text, fontSize = 24.sp)
                if (p.netWorthTrend.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        p.netWorthTrend.forEach { h ->
                            Box(
                                Modifier.weight(
                                    1f,
                                ).fillMaxHeight((h / 100f).coerceIn(0.05f, 1f)).clip(RoundedCornerShape(3.dp)).background(HearthColors.AccentDeep),
                            )
                        }
                    }
                }
            }
        }
        items(p.goals) { g ->
            HearthCard {
                Kicker("Family goal")
                Text(g.name, color = HearthColors.Text, fontSize = 16.sp)
                Bar(g.pct / 100f, HearthColors.Accent, Modifier.padding(vertical = 6.dp))
                Muted("${g.savedLabel} of ${g.targetLabel} · by ${g.etaLabel}", 12)
            }
        }
        item { TextButton(onClick = { dialog = Form.GOAL }) { Text("+ Add a family goal") } }
    }
    dialog?.let { form -> FormDialog(form, s, vm) { dialog = null } }
}

@Composable
fun SettingsScreen(s: UiState, vm: HearthViewModel) {
    val bills = remember(s.input) { Dashboard.bills(s.input) }
    var dialog by remember { mutableStateOf<Form?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Settings") }
        item { SectionTitle("Family members") { LinkText("+ Add") { showInvite = !showInvite } } }
        if (showInvite) {
            item {
                HearthCard {
                    Kicker("Invite code")
                    Text(s.inviteCode ?: "—", color = HearthColors.Accent, fontSize = 22.sp)
                    Muted("On the other phone: install Hearth → Join with invite code, while on this Wi-Fi.", 12)
                }
            }
        }
        items(s.input.members.entries.sortedBy { it.value }) { (id, name) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Avatar(name.take(1).uppercase())
                Spacer(Modifier.width(12.dp))
                Text(name, color = HearthColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (id == s.input.me) Tag("You", HearthColors.Muted)
            }
        }
        item {
            HearthCard(onClick = vm::openSync) {
                Row {
                    Text("Family Wi-Fi sync status", color = HearthColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Muted(if (s.syncRows.isEmpty()) "Not synced yet ›" else "${Dashboard.syncSummary(s.syncRows)} ›")
                }
            }
        }
        item { SectionTitle("Bills & recurring payments") { LinkText("+ Bill") { dialog = Form.BILL } } }
        if (bills.isEmpty()) item { EmptyNote("Add rent, EMIs and subscriptions to see what's due.") }
        items(bills) { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.name, color = HearthColors.Text, fontSize = 14.sp)
                    Muted(b.dueLabel, 12)
                }
                Tag(b.tag, HearthColors.Muted)
                Spacer(Modifier.width(10.dp))
                Text(b.amountLabel, color = HearthColors.Text, fontSize = 14.sp)
            }
        }
        item { SectionTitle("SMS auto-read") }
        item { Segmented(listOf("On", "Off"), if (s.smsEnabled) 0 else 1, { vm.setSmsEnabled(it == 0) }) }
        item { OutlinedButton(onClick = { dialog = Form.BUDGET }, modifier = Modifier.fillMaxWidth()) { Text("Add a monthly budget") } }
        item { OutlinedButton(onClick = vm::syncNow, modifier = Modifier.fillMaxWidth()) { Text("Sync now") } }
        item {
            Text(
                "Log out",
                color = HearthColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { vm.logout() }.padding(12.dp),
            )
        }
    }
    dialog?.let { form -> FormDialog(form, s, vm) { dialog = null } }
}
