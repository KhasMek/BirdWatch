package com.khasmek.flockyou.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khasmek.flockyou.ui.appViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var keyInput by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var seeded by rememberSaveable { mutableStateOf(false) }

    // Prefill the field once the stored key is known (only on first load, not on every recompose).
    LaunchedEffect(state.loaded, state.mapsApiKey) {
        if (state.loaded && !seeded) {
            keyInput = state.mapsApiKey.orEmpty()
            seeded = true
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun handle(result: SaveResult) = when (result) {
        SaveResult.Saved -> toast("API key saved. Open the Map tab to verify it loads.")
        SaveResult.Cleared -> toast("API key removed.")
        is SaveResult.Rejected -> toast(result.reason)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionTitle("Google Maps")
            Text(
                "The wardriving map needs your own Google Maps API key (Maps SDK for Android). " +
                    "It is stored encrypted on this device and never leaves it. Everything else works without one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("API key") },
                placeholder = { Text("AIza…") },
                singleLine = true,
                enabled = state.loaded,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key",
                        )
                    }
                },
                supportingText = {
                    Text(
                        when {
                            !state.loaded -> "Loading…"
                            state.restartRequired -> "Key changed. Restart the app for the map to use it."
                            state.hasKey -> "Key saved (ends in ${state.keyHint})"
                            else -> "No key saved"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.saveMapsApiKey(keyInput, ::handle) },
                    enabled = state.loaded && keyInput.trim() != state.mapsApiKey.orEmpty(),
                ) { Text("Save key") }
                OutlinedButton(
                    onClick = { keyInput = ""; viewModel.clearMapsApiKey(::handle) },
                    enabled = state.loaded && (state.hasKey || keyInput.isNotEmpty()),
                ) { Text("Clear") }
            }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SettingsViewModel.HELP_URL)))
            }) {
                Text("How to get a Maps API key")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.height(16.dp))
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("Alerts")
            ToggleRow(
                title = "Audio alerts",
                subtitle = "Chirp on every new detection (two notes = high confidence, one blip = low)",
                checked = state.audioAlerts,
                onCheckedChange = viewModel::setAudioAlerts,
            )
            TextButton(onClick = viewModel::playTestChirp, enabled = state.audioAlerts) { Text("Play test chirp") }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("Scanning")
            ToggleRow(
                title = "Low-power BLE scan",
                subtitle = "Use SCAN_MODE_LOW_POWER even while the app is open. Saves battery, reacts slower. " +
                    "The app always drops to low power in the background.",
                checked = state.lowPowerScan,
                onCheckedChange = viewModel::setLowPowerScan,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
