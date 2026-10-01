package com.hearth.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearth.app.ui.theme.HearthColors

@Composable
fun OnboardingScreen(s: UiState, vm: HearthViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .background(HearthColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        when (s.controls.onboardStep) {
            0 -> Welcome(vm)
            1 -> FamilySetup(s, vm)
            2 -> SmsPermission(vm)
            else -> AllSet(vm)
        }
    }
}

@Composable
private fun Welcome(vm: HearthViewModel) {
    Spacer(Modifier.size(80.dp))
    Box(
        Modifier.size(56.dp).border(1.5.dp, HearthColors.Accent, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(18.dp).background(HearthColors.Accent, CircleShape))
    }
    Text("Hearth", color = HearthColors.Text, fontSize = 34.sp)
    Muted(
        "Track spending for your whole family, auto-categorized from your SMS, synced the moment everyone's home on the same Wi-Fi.",
        15,
    )
    Button(onClick = vm::nextOnboard, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

@Composable
private fun FamilySetup(s: UiState, vm: HearthViewModel) {
    var create by rememberSaveable { mutableStateOf(true) }
    var familyName by rememberSaveable { mutableStateOf("") }
    var yourName by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var hubUrl by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.findHub() }
    LaunchedEffect(s.controls.foundHub) { s.controls.foundHub?.let { hubUrl = it } }

    Muted("STEP 1 OF 3", 12)
    Text("Set up your family", color = HearthColors.Text, fontSize = 26.sp)
    Muted("Create a new family space, or join one your partner already started.", 14)
    Segmented(listOf("Create a new family", "Join with invite code"), if (create) 0 else 1, { create = it == 0 })
    OutlinedTextField(yourName, { yourName = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (create) {
        OutlinedTextField(familyName, {
            familyName = it
        }, label = { Text("Family name") }, placeholder = { Text("e.g. The Sharma Family") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    } else {
        OutlinedTextField(invite, {
            invite = it.uppercase()
        }, label = { Text("Invite code") }, placeholder = { Text("ABCD-EFGH") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    OutlinedTextField(hubUrl, {
        hubUrl = it
    }, label = { Text("Home hub address") }, placeholder = { Text("http://192.168.1.10:8787") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Muted(
        when {
            s.controls.busy -> "Looking for your Hearth hub on this Wi-Fi…"
            s.controls.foundHub != null -> "Found your hub on this Wi-Fi."
            else -> "Run hearth-server on a computer at home; phones find it automatically."
        },
        12,
    )
    s.controls.error?.let { Text(it, color = HearthColors.Accent, fontSize = 13.sp) }
    TextButton(onClick = vm::findHub) { Text("Search again") }
    val ready = yourName.isNotBlank() && hubUrl.isNotBlank() && (if (create) familyName.isNotBlank() else invite.isNotBlank())
    Button(
        onClick = { vm.setupFamily(create, hubUrl.trim(), familyName.trim(), yourName.trim(), invite.trim()) },
        enabled = ready && !s.controls.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Continue") }
}

@Composable
private fun SmsPermission(vm: HearthViewModel) {
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setSmsEnabled(granted)
        vm.nextOnboard()
    }
    Muted("STEP 2 OF 3", 12)
    Text("Read spending from SMS", color = HearthColors.Text, fontSize = 26.sp)
    HearthCard {
        Text(
            "Hearth scans incoming bank & UPI SMS on your device to log transactions automatically — nothing leaves your phone except the parsed amount, merchant and category.",
            color = HearthColors.Text,
            fontSize = 14.sp,
        )
    }
    Muted("You can turn this off anytime in Settings and add transactions manually instead.", 13)
    Button(
        onClick = {
            asked = true
            launcher.launch(Manifest.permission.RECEIVE_SMS)
        },
        enabled = !asked,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Allow SMS access") }
    TextButton(onClick = {
        vm.setSmsEnabled(false)
        vm.nextOnboard()
    }) { Text("Not now") }
}

@Composable
private fun AllSet(vm: HearthViewModel) {
    Muted("STEP 3 OF 3", 12)
    Text("You're all set", color = HearthColors.Text, fontSize = 26.sp)
    HearthCard {
        Kicker("How family sync works")
        Text(
            "When two or more of you are on the same home Wi-Fi, your spending and budgets sync locally — automatically, with no cloud step in between.",
            color = HearthColors.Text,
            fontSize = 14.sp,
        )
    }
    Button(onClick = vm::finishOnboarding, modifier = Modifier.fillMaxWidth()) { Text("Enter Hearth") }
}
