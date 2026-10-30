package com.halotap.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.halotap.app.DisarmSecret
import com.halotap.app.PinSetupFlow
import com.halotap.app.PinSetupState

/**
 * First run: choose the PIN that will disarm the watch.
 *
 * Asked for twice, because a PIN mistyped once during setup is a PIN the user cannot enter
 * under pressure later — and the only way out of that would be clearing app data.
 *
 * Contacts are not asked for here. Wear OS has no contact picker, so that belongs on the
 * phone; a watch with a PIN and nobody to call is still a working watch.
 */
@Composable
fun SetupScreen(
    onSecretsChosen: (DisarmSecret, DisarmSecret?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The rules live in PinSetupFlow, shared with watchOS. Keeping them in one tested place
    // is why the two watches cannot disagree about what setup does.
    val flow = remember { PinSetupFlow() }
    var state by remember { mutableStateOf<PinSetupState>(flow.state) }
    var entry by remember { mutableStateOf("") }

    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Ink)
                // Swallows stray touches so a tap outside the keys cannot reach a screen
                // underneath during the transition out of setup.
                .pointerInput(key1 = Unit) {},
            contentAlignment = Alignment.Center,
        ) {
            val current = state
            if (current is PinSetupState.OfferDuress) {
                DuressOffer(
                    onSkip = { state = flow.skipDuress().also(onComplete(onSecretsChosen)) },
                    onSet = { state = flow.beginDuress() },
                )
            } else {
                PinKeypad(
                    entry = entry,
                    caption = caption(current),
                    captionTint = if (isProblem(current)) Alert else Dim,
                    onDigit = { digit ->
                        entry += digit
                        if (entry.length >= PIN_LENGTH) {
                            val completed = entry
                            entry = ""
                            state = flow.submit(completed).also(onComplete(onSecretsChosen))
                        }
                    },
                    onBackspace = { entry = entry.dropLast(n = 1) },
                )
            }
        }
    }
}

/** Saving happens at the call site; the flow performs no I/O by design. */
private fun onComplete(
    onSecretsChosen: (DisarmSecret, DisarmSecret?) -> Unit,
): (PinSetupState) -> Unit = { state ->
    if (state is PinSetupState.Complete) onSecretsChosen(state.secret, state.duress)
}

@Composable
private fun DuressOffer(onSkip: () -> Unit, onSet: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 14.dp),
    ) {
        Text(
            text = "Duress code?",
            color = Amber,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "A second code that looks like disarming but silently alerts instead. " +
                "For when someone is standing over you.",
            color = Dim,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        Row {
            SetupChoice(label = "Skip", onClick = onSkip)
            SetupChoice(label = "Set", onClick = onSet)
        }
    }
}

@Composable
private fun SetupChoice(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(all = 3.dp)
            .background(color = Key, shape = CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 13.sp)
    }
}

private fun isProblem(state: PinSetupState): Boolean =
    (state as? PinSetupState.ChoosePin)?.afterMismatch == true ||
        (state as? PinSetupState.ChooseDuress)?.sameAsPin == true

private fun caption(state: PinSetupState): String = when (state) {
    is PinSetupState.ChoosePin ->
        if (state.afterMismatch) "Did not match. Start again" else "Choose a PIN"
    is PinSetupState.ConfirmPin -> "Enter it again"
    is PinSetupState.ChooseDuress ->
        if (state.sameAsPin) "Must differ from your PIN" else "Choose a duress code"
    else -> ""
}
