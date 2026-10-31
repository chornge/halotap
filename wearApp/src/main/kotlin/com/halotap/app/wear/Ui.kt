package com.halotap.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text

internal val Ink = Color(0xFF0B0F14)
internal val Amber = Color(0xFFFFB300)
internal val Alert = Color(0xFFE53935)
internal val Calm = Color(0xFF4CAF50)
internal val Dim = Color(0xFF8A94A6)
internal val Key = Color(0xFF1C2430)

internal const val PIN_LENGTH = 4

/**
 * The digit pad, shared by disarming and by first-run setup.
 *
 * [caption] carries whatever the caller needs to say above the keys — attempts remaining when
 * disarming, the step when setting up — because the keys themselves are identical either way
 * and a second near-copy of this would drift.
 *
 * [remainingFraction] draws the disarm countdown. Null everywhere there is no deadline, which
 * is every use of this pad except the challenge itself.
 */
@Composable
internal fun PinKeypad(
    entry: String,
    caption: String,
    captionTint: Color,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    remainingFraction: Float? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Text(
            text = "•".repeat(entry.length).padEnd(PIN_LENGTH, '·'),
            color = Amber,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        if (remainingFraction != null) {
            CountdownBar(fraction = remainingFraction)
        }
        Text(
            text = caption,
            color = captionTint,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 4.dp, start = 12.dp, end = 12.dp),
        )
        listOf("123", "456", "789").forEach { row ->
            Row {
                row.forEach { digit -> PinKey(label = digit.toString()) { onDigit(digit) } }
            }
        }
        Row {
            PinKey(label = "0") { onDigit('0') }
            PinKey(label = "⌫", onClick = onBackspace)
        }
    }
}

/**
 * How much of the disarm window is left, as a bar that drains left to right.
 *
 * Deliberately not a number. A ticking "7, 6, 5" reads as a threat and is harder to take in
 * at a glance in the dark than a length, and the exact second is not the thing the user needs
 * — whether there is time to finish typing is. It turns red near the end so the state is
 * legible from colour alone, which is also what survives being glanced at sideways.
 */
@Composable
private fun CountdownBar(fraction: Float) {
    Box(
        modifier = Modifier
            .padding(top = 3.dp, bottom = 3.dp)
            .width(width = 88.dp)
            .height(height = 4.dp)
            .background(color = Key, shape = CircleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = fraction.coerceIn(minimumValue = 0f, maximumValue = 1f))
                .fillMaxHeight()
                .background(
                    color = if (fraction > LOW_TIME_FRACTION) Amber else Alert,
                    shape = CircleShape,
                ),
        )
    }
}

/** Below this much of the window left, the countdown turns red. */
private const val LOW_TIME_FRACTION = 0.3f

@Composable
private fun PinKey(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(all = 2.dp)
            .size(size = 34.dp)
            .background(color = Key, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
internal fun Banner(
    headline: String,
    detail: String,
    tint: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = headline, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            text = detail,
            color = Dim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .background(color = Key, shape = CircleShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * What to show under "Alert".
 *
 * Null means the worker has not reported yet, which is the normal case for the first second
 * or so and stays true indefinitely if the app is killed before it finishes. The alert still
 * goes out; nobody is here to read about it.
 */
internal fun String?.describe(): String = when {
    this == null -> "Alerting contacts\u2026"
    contains("delivered") -> "Contacts alerted"
    contains("unconfirmed") -> "Alert sent, delivery unconfirmed"
    all { it.isWhitespace() } || !contains("=") -> "Alerting contacts\u2026"
    none { it == '=' } -> "Could not reach contacts"
    split(", ").all { it.endsWith("not-configured") } -> "No alert channel set up"
    else -> "Could not reach contacts"
}
