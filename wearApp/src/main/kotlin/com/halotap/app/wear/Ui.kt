package com.halotap.app.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 */
@Composable
internal fun PinKeypad(
    entry: String,
    caption: String,
    captionTint: Color,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
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
    }
}
