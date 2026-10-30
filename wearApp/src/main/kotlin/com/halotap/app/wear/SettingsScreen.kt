package com.halotap.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.halotap.app.DisarmSecret
import com.halotap.app.PinSetupFlow
import com.halotap.app.PinSetupState
import com.halotap.app.settings.HalotapSettings

/**
 * Changing the codes, and seeing who would be alerted.
 *
 * Reachable only while idle. Armed, the whole screen is a hold target and a stray tap must
 * never open a menu. Until this existed the PIN could never be changed on Wear OS without
 * clearing app data, which for a code people share or have watched over their shoulder is
 * not a rough edge.
 *
 * Contacts are shown but not editable: Wear OS has no contact picker, so they arrive from
 * the phone. Showing them anyway matters — a user with none needs to know a breach currently
 * reaches nobody.
 */
@Composable
fun SettingsScreen(
    settings: HalotapSettings,
    onSecretChanged: (DisarmSecret) -> Unit,
    onDuressChanged: (DisarmSecret?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Editing?>(null) }

    MaterialTheme {
        Box(modifier = modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            when (val current = editing) {
                null -> Overview(
                    settings = settings,
                    onChangePin = { editing = Editing.Pin },
                    onEditDuress = { secret -> editing = Editing.Duress(secret) },
                    onRemoveDuress = { onDuressChanged(null) },
                    onClose = onClose,
                )

                is Editing.Pin -> CodeEditor(
                    // askForDuress is false: the duress code is edited separately here, and
                    // dragging the user through it on every PIN change would be a trap.
                    flow = remember { PinSetupFlow(askForDuress = false) },
                    firstCaption = "New PIN",
                    onComplete = { done ->
                        onSecretChanged(done.secret)
                        editing = null
                    },
                )

                is Editing.Duress -> CodeEditor(
                    flow = remember(current.secret) {
                        PinSetupFlow(existingSecret = current.secret)
                    },
                    firstCaption = "New duress code",
                    onComplete = { done ->
                        onDuressChanged(done.duress)
                        editing = null
                    },
                )
            }
        }
    }
}

private sealed interface Editing {
    data object Pin : Editing
    data class Duress(val secret: DisarmSecret) : Editing
}

@Composable
private fun Overview(
    settings: HalotapSettings,
    onChangePin: () -> Unit,
    onEditDuress: (DisarmSecret) -> Unit,
    onRemoveDuress: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Settings", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        if (settings.contacts.isEmpty()) {
            // A consequence, not a status. "None" alone would not tell anyone that a breach
            // currently reaches nobody at all.
            Text(
                text = "No one will be alerted. Add contacts on your phone.",
                color = Alert,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = settings.contacts.joinToString(", ") { it.name },
                color = Dim,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }

        SettingsButton(label = "Change PIN", onClick = onChangePin)

        settings.secret?.let { secret ->
            SettingsButton(
                label = if (settings.duressSecret == null) "Set duress code" else "Change duress",
                onClick = { onEditDuress(secret) },
            )
        }
        if (settings.duressSecret != null) {
            SettingsButton(label = "Remove duress", onClick = onRemoveDuress)
        }

        SettingsButton(label = "Done", onClick = onClose)
    }
}

@Composable
private fun CodeEditor(
    flow: PinSetupFlow,
    firstCaption: String,
    onComplete: (PinSetupState.Complete) -> Unit,
) {
    var state by remember(flow) { mutableStateOf(flow.state) }
    var entry by remember(flow) { mutableStateOf("") }

    PinKeypad(
        entry = entry,
        caption = caption(state, firstCaption),
        captionTint = if (isProblem(state)) Alert else Dim,
        onDigit = { digit ->
            entry += digit
            if (entry.length >= PIN_LENGTH) {
                val completed = entry
                entry = ""
                state = flow.submit(completed)
                (state as? PinSetupState.Complete)?.let(onComplete)
            }
        },
        onBackspace = { entry = entry.dropLast(n = 1) },
    )
}

private fun caption(state: PinSetupState, firstCaption: String): String = when (state) {
    is PinSetupState.ChoosePin ->
        if (state.afterMismatch) "Did not match. Start again" else firstCaption
    is PinSetupState.ConfirmPin -> "Enter it again"
    is PinSetupState.ChooseDuress ->
        if (state.sameAsPin) "Must differ from your PIN" else firstCaption
    else -> ""
}

private fun isProblem(state: PinSetupState): Boolean =
    (state as? PinSetupState.ChoosePin)?.afterMismatch == true ||
        (state as? PinSetupState.ChooseDuress)?.sameAsPin == true

@Composable
private fun SettingsButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(color = Key, shape = CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 13.sp)
    }
}
