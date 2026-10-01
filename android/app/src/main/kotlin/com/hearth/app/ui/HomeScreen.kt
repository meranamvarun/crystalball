package com.hearth.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearth.app.ui.theme.HearthColors
import com.hearth.core.Dashboard
import com.hearth.core.Range

private val RANGES = listOf("Day", "Week", "Month", "Year")

@Composable
fun ScopeAndRange(s: UiState, vm: HearthViewModel, showRange: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(listOf("You", "Family"), if (s.controls.family) 1 else 0, { vm.setFamilyScope(it == 1) })
        if (showRange) Segmented(RANGES, s.controls.range.ordinal, { vm.setRange(Range.entries[it]) })
    }
}

@Composable
fun HomeScreen(s: UiState, vm: HearthViewModel) {
    val home = remember(s.input, s.scope, s.controls.range) { Dashboard.home(s.input, s.scope, s.controls.range) }
    val online = s.syncRows.count { it.online }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Muted(s.familyName, 12)
                    Text(home.greeting, color = HearthColors.Text, fontSize = 22.sp)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(HearthColors.Surface)
                        .clickable(onClick = vm::openSync)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(online > 0)
                    Spacer(Modifier.width(6.dp))
                    Muted(if (online > 0) "Synced" else "Offline", 12)
                }
            }
        }
        item { ScopeAndRange(s, vm) }
        item {
            HearthCard {
                Kicker(home.kicker)
                BigNumber(home.totalLabel)
                Muted("${home.deltaLabel} vs last period", 12)
            }
        }
        home.needsReview?.let { review ->
            item {
                HearthCard(onClick = { vm.openCategory(review) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("1 transaction needs a category", color = HearthColors.Text, fontSize = 14.sp)
                            Muted(review.label, 12)
                        }
                        Tag("Review")
                    }
                }
            }
        }
        items(home.alerts) { AlertRow(it) }
        item { SectionTitle("Top categories") { LinkText("View all") { vm.select(Tab.REPORTS) } } }
        if (home.topCategories.isEmpty()) item { EmptyNote("No spending in this period yet.") }
        items(home.topCategories) { CategoryBarRow(it) }
        item { SectionTitle("Recent transactions") }
        if (home.recent.isEmpty()) item { EmptyNote("New bank SMS will show up here automatically.") }
        items(home.recent, key = { it.transactionId }) { TxnRowView(it) }
        item { Spacer(Modifier.fillMaxWidth().padding(8.dp)) }
    }
}
