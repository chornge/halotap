package com.halotap.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.halotap.app.DisarmSecret
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.HttpEscalationConfig
import com.halotap.app.settings.HalotapSettings
import com.halotap.app.settings.SettingsStore

/**
 * Onboarding, on the phone because neither watch can do it.
 *
 * Wear OS has no contact picker and watchOS ships no ContactsUI, so choosing who to alert has
 * to happen here. Every change is written through immediately rather than behind a Save
 * button: there is no half-configured state worth modelling, and a user who sets a PIN and
 * closes the app should not find it unset.
 */
@Composable
fun SetupScreen(
    store: SettingsStore,
    onPickContact: () -> Unit,
    settings: HalotapSettings,
    onSettingsChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // enableEdgeToEdge draws under the status and navigation bars, so without this
            // the heading sits behind the clock.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(all = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Halotap setup", style = MaterialTheme.typography.headlineSmall)

        if (!settings.canEscalate) {
            Text(
                "No one will be alerted yet. Add a contact, or an alert endpoint below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        PinCard(settings = settings, store = store, onChanged = onSettingsChanged)
        DuressCard(settings = settings, store = store, onChanged = onSettingsChanged)
        ContactsCard(
            contacts = settings.contacts,
            onAdd = onPickContact,
            onRemove = { contact ->
                store.saveContacts(settings.contacts - contact)
                onSettingsChanged()
            },
        )
        EndpointCard(settings = settings, store = store, onChanged = onSettingsChanged)
    }
}

@Composable
private fun PinCard(settings: HalotapSettings, store: SettingsStore, onChanged: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Disarm PIN", style = MaterialTheme.typography.titleMedium)
            Text(
                if (settings.isConfigured) "A PIN is set." else "No PIN set. The watch cannot arm.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it.filter(Char::isDigit); error = null },
                label = { Text("New PIN") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    // DisarmSecret validates length and content, so the UI does not restate
                    // the rules and cannot drift from them.
                    runCatching { DisarmSecret.ofPin(entry) }
                        .onSuccess {
                            store.saveSecret(it)
                            entry = ""
                            onChanged()
                        }
                        .onFailure { error = it.message }
                },
                enabled = entry.isNotEmpty(),
            ) { Text("Save PIN") }
        }
    }
}

@Composable
private fun DuressCard(settings: HalotapSettings, store: SettingsStore, onChanged: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Duress code", style = MaterialTheme.typography.titleMedium)
            Text(
                "A second code that looks exactly like disarming but silently alerts your " +
                    "contacts instead. For when someone is standing over you. The watch shows " +
                    "the same screen either way, so it cannot be told apart.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (settings.duressSecret != null) "A duress code is set." else "Not set.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it.filter(Char::isDigit); error = null },
                label = { Text("Duress code") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching {
                            val duress = DisarmSecret.ofPin(entry)
                            // Refused rather than silently useless: the disarm branch is
                            // checked first, so an identical duress code could never fire.
                            require(settings.secret?.matches(entry) != true) {
                                "The duress code must differ from your PIN"
                            }
                            duress
                        }
                            .onSuccess { store.saveDuressSecret(it); entry = ""; onChanged() }
                            .onFailure { error = it.message }
                    },
                    enabled = entry.isNotEmpty(),
                ) { Text("Save") }
                if (settings.duressSecret != null) {
                    TextButton(onClick = { store.saveDuressSecret(null); onChanged() }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsCard(
    contacts: List<EmergencyContact>,
    onAdd: () -> Unit,
    onRemove: (EmergencyContact) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Emergency contacts", style = MaterialTheme.typography.titleMedium)
            if (contacts.isEmpty()) {
                Text("None yet.", style = MaterialTheme.typography.bodySmall)
            }
            contacts.forEach { contact ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(contact.name, style = MaterialTheme.typography.bodyMedium)
                        Text(contact.phoneNumber, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onRemove(contact) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
            Button(onClick = onAdd) { Text("Add contact") }
        }
    }
}

@Composable
private fun EndpointCard(settings: HalotapSettings, store: SettingsStore, onChanged: () -> Unit) {
    val current = settings.http
    var kind by remember(current) {
        mutableStateOf(
            when (current) {
                null -> EndpointKind.NONE
                is HttpEscalationConfig.Webhook -> EndpointKind.WEBHOOK
                is HttpEscalationConfig.Twilio -> EndpointKind.TWILIO
            },
        )
    }
    var url by remember(current) {
        mutableStateOf((current as? HttpEscalationConfig.Webhook)?.url.orEmpty())
    }
    var sid by remember(current) {
        mutableStateOf((current as? HttpEscalationConfig.Twilio)?.accountSid.orEmpty())
    }
    var token by remember(current) {
        mutableStateOf((current as? HttpEscalationConfig.Twilio)?.authToken.orEmpty())
    }
    var from by remember(current) {
        mutableStateOf((current as? HttpEscalationConfig.Twilio)?.fromNumber.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your own alert endpoint", style = MaterialTheme.typography.titleMedium)
            Text(
                "Optional, and added alongside the watch's own alarm rather than replacing it. " +
                    "On Apple Watch this is the only way to reach more than one person.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EndpointKind.entries.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option; error = null },
                        label = { Text(option.label) },
                    )
                }
            }
            when (kind) {
                EndpointKind.NONE -> Unit
                EndpointKind.WEBHOOK -> OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("HTTPS URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EndpointKind.TWILIO -> {
                    OutlinedTextField(
                        value = sid,
                        onValueChange = { sid = it; error = null },
                        label = { Text("Account SID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; error = null },
                        label = { Text("Auth token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it; error = null },
                        label = { Text("From number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    runCatching {
                        when (kind) {
                            EndpointKind.NONE -> null
                            EndpointKind.WEBHOOK -> HttpEscalationConfig.Webhook(url.trim())
                            EndpointKind.TWILIO ->
                                HttpEscalationConfig.Twilio(sid.trim(), token.trim(), from.trim())
                        }
                    }
                        .onSuccess { store.saveHttpConfig(it); error = null; onChanged() }
                        .onFailure { error = it.message }
                },
            ) { Text("Save endpoint") }
        }
    }
}

private enum class EndpointKind(val label: String) {
    NONE("None"),
    WEBHOOK("Webhook"),
    TWILIO("Twilio"),
}
