package com.hearth.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearth.app.ui.theme.HearthColors
import com.hearth.core.CategoryRow
import com.hearth.core.TxnRow

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HearthColors.SurfaceHigh)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) HearthColors.Accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) HearthColors.Accent else HearthColors.Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun HearthCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(HearthColors.Surface)
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
fun Kicker(text: String) = Text(text.uppercase(), color = HearthColors.Muted, fontSize = 11.sp, letterSpacing = 1.sp)

@Composable
fun BigNumber(text: String) = Text(text, color = HearthColors.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Muted(text: String, size: Int = 13) = Text(text, color = HearthColors.Muted, fontSize = size.sp)

@Composable
fun ScreenTitle(text: String) = Text(text, color = HearthColors.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun SectionTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = HearthColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun LinkText(text: String, onClick: () -> Unit) =
    Text(text, color = HearthColors.Accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClick).padding(4.dp))

@Composable
fun Tag(text: String, color: Color = HearthColors.Accent, filled: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color.copy(alpha = 0.2f) else HearthColors.SurfaceHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(HearthColors.Neutral),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

@Composable
fun Avatar(initial: String, color: Color = HearthColors.SurfaceHigh) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(initial, color = HearthColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Dot(on: Boolean) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) HearthColors.Accent else HearthColors.Neutral))
}

@Composable
fun AlertRow(text: String) {
    HearthCard {
        Row(verticalAlignment = Alignment.Top) {
            Tag("Alert", HearthColors.Accent2, filled = true)
            Spacer(Modifier.width(10.dp))
            Text(text, color = HearthColors.Text, fontSize = 13.sp)
        }
    }
}

@Composable
fun CategoryBarRow(row: CategoryRow, showPct: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(row.name)
            Spacer(Modifier.weight(1f))
            Text(row.amountLabel, color = HearthColors.Text, fontSize = 13.sp)
            if (showPct) Muted(" · ${row.pctLabel}")
        }
        Bar(row.pct / 100f, HearthColors.Accent)
    }
}

@Composable
fun TxnRowView(row: TxnRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(row.memberInitial)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.merchant, color = HearthColors.Text, fontSize = 14.sp)
            Muted("${row.dateLabel} · ${row.categoryLabel}", 12)
        }
        Text(row.amountLabel, color = HearthColors.Text, fontSize = 14.sp)
    }
}

@Composable
fun EmptyNote(text: String) = Muted(text)
