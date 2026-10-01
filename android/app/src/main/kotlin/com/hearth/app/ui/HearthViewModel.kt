package com.hearth.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hearth.app.HearthApp
import com.hearth.app.sync.SyncScheduler
import com.hearth.core.Dashboard
import com.hearth.core.DashboardInput
import com.hearth.core.Range
import com.hearth.core.ReviewItem
import com.hearth.core.Scope
import com.hearth.core.SyncRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.IOException

enum class Tab(val label: String) {
    HOME("Home"),
    REPORTS("Reports"),
    ANALYTICS("Analytics"),
    INVEST("Invest"),
    SETTINGS("Settings"),
}

sealed interface Sheet {
    data class Category(val transactionId: String, val label: String) : Sheet
    data object Sync : Sheet
}

/** Ephemeral UI selections (not persisted). */
data class Controls(
    val tab: Tab = Tab.HOME,
    val family: Boolean = false,
    val range: Range = Range.MONTH,
    val sheet: Sheet? = null,
    val onboardStep: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val foundHub: String? = null,
)

data class UiState(
    val onboarded: Boolean,
    val controls: Controls,
    val input: DashboardInput,
    val familyName: String,
    val inviteCode: String?,
    val smsEnabled: Boolean,
    val syncRows: List<SyncRow>,
    val categories: List<String>,
) {
    val scope: Scope get() = if (controls.family) Scope.Family else Scope.Member(input.me)
}

class HearthViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as HearthApp).graph
    private val controls = MutableStateFlow(Controls())
    private val minuteTicks = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val state: StateFlow<UiState?> = combine(graph.repo.ledger, graph.prefs.version, controls, minuteTicks) { ledger, _, c, now ->
        val p = graph.prefs
        UiState(
            onboarded = p.onboarded,
            controls = c,
            input = DashboardInput(ledger, p.memberId.orEmpty(), p.memberNames, p.tzOffsetMinutes, now),
            familyName = p.familyName,
            inviteCode = p.inviteCode,
            smsEnabled = p.smsEnabled,
            syncRows = p.lastStatus?.let { Dashboard.syncRows(it, ONLINE_WINDOW_MS) }.orEmpty(),
            categories = graph.repo.categories(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(tab: Tab) = controls.update { it.copy(tab = tab) }
    fun setFamilyScope(family: Boolean) = controls.update { it.copy(family = family) }
    fun setRange(range: Range) = controls.update { it.copy(range = range) }
    fun openCategory(item: ReviewItem) = controls.update { it.copy(sheet = Sheet.Category(item.transactionId, item.label)) }
    fun openSync() = controls.update { it.copy(sheet = Sheet.Sync) }
    fun closeSheet() = controls.update { it.copy(sheet = null) }

    fun pickCategory(transactionId: String, category: String) = viewModelScope.launch {
        graph.repo.recategorize(transactionId, category, System.currentTimeMillis())
        closeSheet()
        SyncScheduler.syncNow(getApplication())
    }

    fun put(entity: String, id: String, payload: JsonObject) = viewModelScope.launch {
        graph.repo.put(entity, id, payload, System.currentTimeMillis())
        SyncScheduler.syncNow(getApplication())
    }

    // ---- onboarding ----

    fun nextOnboard() = controls.update { it.copy(onboardStep = it.onboardStep + 1, error = null) }

    fun findHub() = viewModelScope.launch {
        controls.update { it.copy(busy = true, error = null) }
        val url = graph.locateHub()
        controls.update { it.copy(busy = false, foundHub = url, error = if (url == null) "No hub found on this Wi-Fi. Enter its address." else null) }
    }

    fun setupFamily(create: Boolean, hubUrl: String, familyName: String, yourName: String, inviteCode: String) = viewModelScope.launch {
        controls.update { it.copy(busy = true, error = null) }
        try {
            if (create) graph.createFamily(hubUrl, familyName, yourName) else graph.joinFamily(hubUrl, inviteCode, yourName)
            controls.update { it.copy(busy = false, onboardStep = it.onboardStep + 1) }
        } catch (e: IOException) {
            controls.update { it.copy(busy = false, error = "Couldn't reach the hub: ${e.message}") }
        } catch (e: IllegalArgumentException) {
            controls.update { it.copy(busy = false, error = "Check the hub address: ${e.message}") }
        }
    }

    fun setSmsEnabled(enabled: Boolean) {
        graph.prefs.smsEnabled = enabled
    }

    fun finishOnboarding() {
        graph.prefs.onboarded = true
        SyncScheduler.syncNow(getApplication())
    }

    fun syncNow() = viewModelScope.launch { graph.sync() }

    fun logout() = viewModelScope.launch {
        graph.logout()
        controls.value = Controls()
    }

    companion object {
        /** A member counts as "online" if their phone synced within this window. */
        const val ONLINE_WINDOW_MS = 15 * 60_000L
    }
}
