package com.halotap.app.wear

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableIntStateOf
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
    onOpenSettings: () -> Unit,
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
                    onOpenSettings = onOpenSettings.takeIf {
                        // Only while idle. Armed, the whole screen is a hold target and a
                        // stray tap must never open a menu.
                        state.appearance() == StandbyAppearance.HOLD
                    },
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
                    ChallengePane(
                        attemptsRemaining = challengeNow.attemptsRemaining,
                        deadlineMillis = challengeNow.deadlineMillis,
                        remainingMillis = {
                            session.remainingMillis(SystemClock.elapsedRealtime())
                        },
                        remainingFraction = {
                            session.remainingFraction(SystemClock.elapsedRealtime()).toFloat()
                        },
                        entry = entry,
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

                // The alert has already gone out, so standing down is not a second chance
                // at the challenge -- it is the way back to a usable watch. Without it the
                // app is stranded here: leaving and returning resumes the same screen, and
                // on watchOS the crown only suspends, so there is nothing to "close".
                StandbyAppearance.ALERT -> AlertPane(
                    detail = summary.describe(),
                    waitMillis = { session.standDownDelayMillis(SystemClock.elapsedRealtime()) },
                    onEnterPin = { pin ->
                        state = session.clearBreach(pin, SystemClock.elapsedRealtime())
                        val cleared = state !is StandbyState.Breached
                        if (cleared) {
                            // The escalation itself is left alone: it may still be waiting
                            // on the radio, and this says the user is safe now, not that
                            // the contacts should be left guessing.
                            breaches.alertCleared()
                            armedMarkers.save(sinceMillis = null)
                        }
                        cleared
                    },
                )
            }
        }
    }
}

/**
 * The disarm keypad, with the countdown that says how long is left.
 *
 * The drain is animated rather than polled: the session's own tick runs at 250ms, which is
 * fine for deciding a breach but visibly steppy as a bar. Both start from the same reading of
 * the same clock and the session stays the authority on when the deadline actually passes --
 * the bar reports it, it does not decide it.
 */
@Composable
private fun ChallengePane(
    attemptsRemaining: Int,
    deadlineMillis: Long,
    remainingMillis: () -> Long,
    remainingFraction: () -> Float,
    entry: String,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    // Keyed on the deadline, so the first wrong entry -- which buys a fresh ten seconds --
    // refills the bar instead of carrying on from wherever the last one had drained to.
    val drain = remember(deadlineMillis) { Animatable(initialValue = remainingFraction()) }
    LaunchedEffect(deadlineMillis) {
        drain.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = remainingMillis().toInt().coerceAtLeast(minimumValue = 1),
                easing = LinearEasing,
            ),
        )
    }

    PinKeypad(
        entry = entry,
        caption = if (attemptsRemaining > 1) "$attemptsRemaining tries left" else "Last try",
        captionTint = if (attemptsRemaining > 1) Dim else Alert,
        remainingFraction = drain.value,
        onDigit = onDigit,
        onBackspace = onBackspace,
    )
}

// Owns its own keypad state, so the screen above does not have to.
@Composable
private fun AlertPane(
    detail: String,
    waitMillis: () -> Long,
    onEnterPin: (String) -> Boolean,
) {
    var clearing by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf("") }
    var waitSeconds by remember { mutableIntStateOf(0) }

    // Only polls while the keypad is up, which is only during an alert someone is clearing.
    LaunchedEffect(clearing) {
        while (clearing) {
            waitSeconds = ((waitMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
            delay(timeMillis = 200)
        }
    }

    if (clearing) {
        PinKeypad(
            entry = entry,
            caption = if (waitSeconds > 0) "Wait ${waitSeconds}s" else "PIN to stand down",
            captionTint = if (waitSeconds > 0) Alert else Dim,
            onDigit = { digit ->
                // Swallowed rather than queued during the wait. Letting the digits land would
                // spend the attempt the moment the wait ended, on a code typed before it.
                if (waitSeconds == 0) {
                    entry += digit
                    if (entry.length >= PIN_LENGTH) {
                        // A wrong code leaves the alert standing and buys a longer wait. There
                        // is no attempt limit: nothing worse remains to trigger, and locking a
                        // safe user out of their own watch would be the worse failure.
                        if (onEnterPin(entry)) clearing = false
                        entry = ""
                    }
                }
            },
            onBackspace = { entry = entry.dropLast(n = 1) },
        )
    } else {
        Banner(
            headline = "Alert",
            detail = detail,
            tint = Alert,
            actionLabel = "I'm safe",
            onAction = { clearing = true },
        )
    }
}

private const val MILLIS_PER_SECOND = 1_000L

@Composable
private fun HoldPad(
    armed: Boolean,
    lowBatteryPercent: Int?,
    onOpenSettings: (() -> Unit)?,
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
        onOpenSettings?.let { open ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Well clear of the bezel: on a round screen the top of the display is
                    // curved away, and a control flush to it is clipped and hard to hit.
                    .padding(top = 22.dp)
                    .background(color = Key, shape = CircleShape)
                    .clickable(onClick = open)
                    .padding(horizontal = 12.dp, vertical = 3.dp),
            ) {
                Text(text = "settings", color = Dim, fontSize = 9.sp)
            }
        }

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
