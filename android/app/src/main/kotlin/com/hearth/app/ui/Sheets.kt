package com.hearth.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearth.app.ui.theme.HearthColors
import com.hearth.core.AssetClass
import com.hearth.core.Bill
import com.hearth.core.BillKind
import com.hearth.core.Budget
import com.hearth.core.Entities
import com.hearth.core.Goal
import com.hearth.core.Holding
import com.hearth.core.parseInrToMinor
import com.hearth.core.toPayload
import java.time.YearMonth
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheets(s: UiState, vm: HearthViewModel) {
    val sheet = s.controls.sheet ?: return
    ModalBottomSheet(onDismissRequest = vm::closeSheet, containerColor = HearthColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (sheet) {
                is Sheet.Category -> {
                    Text("Choose a category", color = HearthColors.Text, fontSize = 18.sp)
                    Muted(sheet.label, 13)
                    Muted("Hearth will remember this merchant for the whole family.", 12)
                    s.categories.filter { it != "Income" }.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                Text(
                                    c,
                                    color = HearthColors.Accent,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable { vm.pickCategory(sheet.transactionId, c) }.padding(8.dp),
                                )
                            }
                        }
                    }
                }
                Sheet.Sync -> {
                    Text("Family Wi-Fi sync", color = HearthColors.Text, fontSize = 18.sp)
                    Muted("Everyone syncs when on the same home network — no cloud step needed.", 13)
                    if (s.syncRows.isEmpty()) Muted("No sync yet. Make sure the Hearth hub is running at home.", 13)
                    s.syncRows.forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(row.initial)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.name, color = HearthColors.Text, fontSize = 14.sp)
                                Muted(row.status, 12)
                            }
                            Dot(row.online)
                        }
                    }
                }
            }
            TextButton(onClick = vm::closeSheet, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}

enum class Form(val title: String, val fields: List<String>) {
    HOLDING("Add a holding", listOf("Name", "Type (equity, mutual_fund, fd, gold, cash, other)", "Amount invested (₹)", "Current value (₹)")),
    BUDGET("Add a monthly budget", listOf("Category", "Monthly limit (₹)", "Just for me? (yes/no)")),
    BILL("Add a bill", listOf("Name", "Amount (₹)", "Due day of month (1-31)", "Kind (recurring, variable, autopay)")),
    GOAL("Add a family goal", listOf("Name", "Target (₹)", "Saved so far (₹)", "Target month (YYYY-MM)")),
}

/** Simple text form → validated entity → local edit (synced to the family on the next round). */
@Composable
fun FormDialog(form: Form, s: UiState, vm: HearthViewModel, onDismiss: () -> Unit) {
    val values = remember { mutableStateMapOf<Int, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    fun v(i: Int) = values[i].orEmpty().trim()

    fun submit() {
        val id = UUID.randomUUID().toString()
        val me = s.input.me
        val payload = when (form) {
            Form.HOLDING -> {
                val cls = AssetClass.entries.firstOrNull { it.wire == v(1).lowercase() } ?: return run { error = "Unknown type" }
                val invested = parseInrToMinor(v(2)) ?: return run { error = "Invalid amount invested" }
                val value = parseInrToMinor(v(3)) ?: return run { error = "Invalid current value" }
                if (v(0).isEmpty()) return run { error = "Name is required" }
                Entities.HOLDING to toPayload(Holding(memberId = me, name = v(0), assetClass = cls, investedMinor = invested, valueMinor = value))
            }
            Form.BUDGET -> {
                val cap = parseInrToMinor(v(1))?.takeIf { it > 0 } ?: return run { error = "Invalid limit" }
                val category = s.categories.firstOrNull { it.equals(v(0), ignoreCase = true) } ?: return run { error = "Unknown category" }
                val mine = v(2).lowercase().startsWith("y")
                Entities.BUDGET to toPayload(Budget(category = category, capMinor = cap, memberId = if (mine) me else null))
            }
            Form.BILL -> {
                val amount = parseInrToMinor(v(1)) ?: return run { error = "Invalid amount" }
                val day = v(2).toIntOrNull()?.takeIf { it in 1..31 } ?: return run { error = "Due day must be 1-31" }
                val kind = BillKind.entries.firstOrNull { it.name.equals(v(3), ignoreCase = true) } ?: BillKind.RECURRING
                if (v(0).isEmpty()) return run { error = "Name is required" }
                Entities.BILL to toPayload(Bill(name = v(0), amountMinor = amount, dueDay = day, kind = kind))
            }
            Form.GOAL -> {
                val target = parseInrToMinor(v(1))?.takeIf { it > 0 } ?: return run { error = "Invalid target" }
                val saved = parseInrToMinor(v(2).ifEmpty { "0" }) ?: return run { error = "Invalid saved amount" }
                val month = runCatching { YearMonth.parse(v(3)).toString() }.getOrNull() ?: return run { error = "Month must be YYYY-MM" }
                if (v(0).isEmpty()) return run { error = "Name is required" }
                Entities.GOAL to toPayload(Goal(name = v(0), targetMinor = target, savedMinor = saved, targetMonth = month))
            }
        }
        vm.put(payload.first, id, payload.second)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(form.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                form.fields.forEachIndexed { i, label ->
                    OutlinedTextField(values[i].orEmpty(), { values[i] = it }, label = { Text(label) }, singleLine = true)
                }
                error?.let { Text(it, color = HearthColors.Accent, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
