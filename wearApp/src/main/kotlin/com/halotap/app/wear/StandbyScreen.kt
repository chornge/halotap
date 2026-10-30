package com.halotap.app.wear

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.halotap.app.DisarmSecret
import com.halotap.app.StandbySession
import com.halotap.app.StandbyAppearance
import com.halotap.app.StandbyState
import com.halotap.app.appearance
import com.halotap.app.shouldStandDown
import com.halotap.app.settings.ArmedMarkerStore
import com.halotap.app.wear.alarm.BreachCoordinator
import kotlinx.coroutines.delay

/**
 * The whole app, as one screen.
 *
 * Time comes from [SystemClock.elapsedRealtime], not the wall clock, so that changing the
 * watch's time cannot push the challenge deadline out and stall an escalation.
 */
@Composable
fun StandbyScreen(
    secret: DisarmSecret,
    duressSecret: DisarmSecret?,
    breaches: BreachCoordinator,
    armedMarkers: ArmedMarkerStore,
    modifier: Modifier = Modifier,
) {
    val session = remember(secret, duressSecret) {
        StandbySession(secret = secret, duressSecret = duressSecret)
    }

    // An incoming call recreates this activity, which takes the session with it. Without
    // this the user comes back silently unarmed: no false alarm, but no protection either,
    // and nothing tells them. Runs once per session, before the first frame, so the screen
    // never flashes HOLD on its way to ON WATCH.
    var state by remember {
        val marker = armedMarkers.load()
        val now = SystemClock.elapsedRealtime()
        val restored = marker
            ?.takeIf { StandbySession.isArmedMarkerUsable(it, now) }
            ?.let { session.restoreArmed(it) }
        if (marker != null && restored == null) {
            // Rebooted, or older than the window. Drop it rather than leaving it to be
            // reconsidered on every launch.
            armedMarkers.save(sinceMillis = null)
        }
        mutableStateOf(restored ?: session.state)
    }
    var entry by remember { mutableStateOf("") }
    // Reported by the worker, not by this screen. The screen may well be gone by the time
    // the alert is actually sent.
    val summary by breaches.summaries().collectAsState(initial = null)

    // Read once per session rather than polled: the number only has to be roughly right,
    // and a battery poll on every recomposition would itself cost charge.
    val context = LocalContext.current
    val lowBattery = remember { batteryPercent(context)?.takeIf { it <= LOW_BATTERY_PERCENT } }

    // While a challenge is open, nothing else will advance it: an attacker who simply waits
    // has to be escalated on, so the deadline needs its own heartbeat.
    val challenge = state as? StandbyState.Challenge
    LaunchedEffect(challenge) {
        if (challenge != null) {
            // Hand the deadline to the system before anything else. From here the breach
            // happens whether or not this process survives the next ten seconds.
            breaches.challengeOpened(challenge.deadlineMillis)
            while (session.state is StandbyState.Challenge) {
                delay(timeMillis = 250)
                state = session.tick(SystemClock.elapsedRealtime())
            }
            armedMarkers.save(sinceMillis = null)
        }
    }

    // Matches watchOS, which cannot close itself at all: show the confirmation, then return
    // to Idle so the watch is ready for the next walk rather than stranded on a dead-end
    // screen. Keeping the two platforms identical is worth more than Android's ability to
    // finish its own task.
    // Keyed on appearance so a duress breach clears exactly as a real disarm does, after
    // the same delay. standDown() is gated on the state, not the appearance: calling it for
    // duress would cancel the escalation that was just started.
    LaunchedEffect(state) {
        if (state.appearance() == StandbyAppearance.DISARMED) {
            if (state.shouldStandDown()) breaches.standDown()
            delay(timeMillis = 600)
            entry = ""
            armedMarkers.save(sinceMillis = null)
            state = session.reset()
        }
    }

    // Keyed on the state object, so this fires once per breach rather than on every
    // recomposition. Raising the same alarm twice would be worse than noise: it would
    // double-text the contacts.
    val breached = state as? StandbyState.Breached
    LaunchedEffect(breached) {
        if (breached != null) {
            // A deadline breach was already scheduled, and the worker's unique name makes
            // this a no-op in that case. A wrong code or the duress code arrives before the
            // deadline, so this is what sends those.
            breaches.breachNow(breached.reason)
        }
    }

    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) {
            // Switched on appearance, not state. A duress breach must be drawn exactly as
            // a real disarm: anything that lets an onlooker tell them apart defeats it.
            when (state.appearance()) {
                StandbyAppearance.HOLD, StandbyAppearance.ARMED -> HoldPad(
                    armed = state.appearance() == StandbyAppearance.ARMED,
                    lowBatteryPercent = lowBattery,
                    onHold = {
                        val now = SystemClock.elapsedRealtime()
                        state = session.hold(now)
                        (state as? StandbyState.Armed)?.let { armedMarkers.save(it.heldSinceMillis) }
                    },
                    onRelease = {
                        state = session.release(SystemClock.elapsedRealtime())
                        // The hold is over however this ended. Left behind, the marker would
                        // re-arm the app on the next launch.
                        if (state !is StandbyState.Armed) armedMarkers.save(sinceMillis = null)
                    },
                )

                StandbyAppearance.CHALLENGE -> {
                    val challengeNow = state as StandbyState.Challenge
                    PinKeypad(
                        entry = entry,
                        caption = if (challengeNow.attemptsRemaining > 1) {
                            "${challengeNow.attemptsRemaining} tries left"
                        } else {
                            "Last try"
                        },
                        captionTint = if (challengeNow.attemptsRemaining > 1) Dim else Alert,
                        onDigit = { digit ->
                            entry += digit
                            if (entry.length >= PIN_LENGTH) {
                                state = session.submit(entry, SystemClock.elapsedRealtime())
                                entry = ""
                            }
                        },
                        onBackspace = { entry = entry.dropLast(n = 1) },
                    )
                }

                StandbyAppearance.DISARMED -> Banner(
                    headline = "Disarmed",
                    detail = "Stay safe",
                    tint = Calm,
                )

                StandbyAppearance.ALERT -> Banner(
                    headline = "Alert",
                    detail = summary.describe(),
                    tint = Alert,
                )
            }
        }
    }
}

@Composable
private fun HoldPad(
    armed: Boolean,
    lowBatteryPercent: Int?,
    onHold: () -> Unit,
    onRelease: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(key1 = Unit) {
                detectTapGestures(
                    onPress = {
                        onHold()
                        // true means the finger was genuinely lifted. false means the gesture
                        // was cancelled, and on a watch that is the system taking the screen:
                        // an incoming call, a system dialog. That is not the user letting go.
                        //
                        // Treating a cancel as a release is how a phone call turns into a
                        // false alarm: the challenge starts silently behind the call UI and
                        // breaches ten seconds later, texting the user's emergency contacts
                        // while they are simply answering their phone. An app that cries wolf
                        // gets uninstalled, which is the worst failure this one has.
                        //
                        // So stay armed. The user is still on watch when they come back, and
                        // a tap releases normally -- hold() is a no-op once armed, and the
                        // lift that ends that tap calls release().
                        if (tryAwaitRelease()) {
                            onRelease()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (armed) "ON WATCH" else "HOLD",
                color = if (armed) Amber else Dim,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (armed) "Release when safe" else "Press and hold to arm",
                color = Dim,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
            )
            // Stated plainly, including the consequence. "Low battery" on its own would not
            // tell anyone that the alert simply does not happen if the watch dies holding.
            lowBatteryPercent?.let { percent ->
                Text(
                    text = "Battery $percent%. If the watch dies, no alert is sent.",
                    color = Alert,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 14.dp, end = 14.dp),
                )
            }
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
private fun String?.describe(): String = when {
    this == null -> "Alerting contacts\u2026"
    contains("delivered") -> "Contacts alerted"
    contains("unconfirmed") -> "Alert sent, delivery unconfirmed"
    all { it.isWhitespace() } || !contains("=") -> "Alerting contacts\u2026"
    none { it == '=' } -> "Could not reach contacts"
    split(", ").all { it.endsWith("not-configured") } -> "No alert channel set up"
    else -> "Could not reach contacts"
}

