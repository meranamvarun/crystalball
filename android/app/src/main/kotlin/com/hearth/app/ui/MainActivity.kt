package com.hearth.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hearth.app.ui.theme.HearthColors
import com.hearth.app.ui.theme.HearthTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HearthTheme { HearthRoot() } }
    }
}

@Composable
fun HearthRoot(vm: HearthViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state
    when {
        s == null -> Box(Modifier.fillMaxSize().background(HearthColors.Bg))
        !s.onboarded -> OnboardingScreen(s, vm)
        else -> MainScaffold(s, vm)
    }
}

@Composable
private fun MainScaffold(s: UiState, vm: HearthViewModel) {
    Scaffold(
        containerColor = HearthColors.Bg,
        bottomBar = {
            NavigationBar(containerColor = HearthColors.Surface) {
                Tab.entries.forEach { tab ->
                    val selected = s.controls.tab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { vm.select(tab) },
                        icon = {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (selected) HearthColors.Accent else HearthColors.NeutralLight),
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = HearthColors.Accent,
                            unselectedTextColor = HearthColors.Muted,
                            indicatorColor = HearthColors.SurfaceHigh,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(HearthColors.Bg)) {
            when (s.controls.tab) {
                Tab.HOME -> HomeScreen(s, vm)
                Tab.REPORTS -> ReportsScreen(s, vm)
                Tab.ANALYTICS -> AnalyticsScreen(s, vm)
                Tab.INVEST -> InvestScreen(s, vm)
                Tab.SETTINGS -> SettingsScreen(s, vm)
            }
        }
    }
    Sheets(s, vm)
}
