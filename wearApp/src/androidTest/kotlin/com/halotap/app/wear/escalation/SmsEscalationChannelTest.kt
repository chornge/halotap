package com.halotap.app.wear.escalation

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.BeforeTest
import com.halotap.app.BreachReason
import com.halotap.app.escalation.BreachContext
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.EscalationOutcome
import com.halotap.app.escalation.SmsEscalationChannel
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The send path, which had no test at all until now — the single most important code in the
 * app and the only part a person's safety actually rests on.
 */
class SmsEscalationChannelTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @BeforeTest
    fun grantSendSms() {
        // connectedAndroidTest reinstalls the app, which drops any grant made with
        // `adb pm grant`, and SEND_SMS is restricted so it is not auto-granted. Without
        // this the channel short-circuits on the permission check and never reaches the
        // code these tests exist to cover.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.SEND_SMS)
    }

    private fun breach(vararg contacts: EmergencyContact) = BreachContext(
        reason = BreachReason.DEADLINE_PASSED,
        occurredAtMillis = 0,
        contacts = contacts.toList(),
        message = "Halotap test, please ignore.",
    )

    /** Drives the suspending call to completion from a plain JUnit test. */
    private fun escalate(channel: SmsEscalationChannel, breach: BreachContext): EscalationOutcome {
        var outcome: EscalationOutcome? = null
        val done = CountDownLatch(1)
        suspend { channel.escalate(breach) }.startCoroutine(
            Continuation(EmptyCoroutineContext) { result ->
                outcome = result.getOrThrow()
                done.countDown()
            },
        )
        assertTrue(done.await(60, TimeUnit.SECONDS), "escalate() never completed")
        return outcome!!
    }

    @Test
    fun noContactsIsNotConfiguredRatherThanAFailure() {
        val channel = SmsEscalationChannel(context)
        assertEquals(EscalationOutcome.NotConfigured, escalate(channel, breach()))
    }

    @Test
    fun aSendWithNoConfirmationIsUnconfirmedNotDelivered() {
        // The regression test for the bug this channel shipped with. sendTextMessage is
        // asynchronous and returns void: it throws only on malformed arguments, never when
        // there is no signal. Counting a call that did not throw as success meant telling
        // someone their contacts were alerted from a car park with no bars.
        //
        // A one-millisecond window guarantees no result can arrive in time, so anything
        // other than Unconfirmed here means the channel is inventing an outcome again.
        val channel = SmsEscalationChannel(context, resultTimeoutMillis = 1)
        val outcome = escalate(channel, breach(EmergencyContact("Test", "+15555551234")))

        assertIs<EscalationOutcome.Unconfirmed>(outcome)
        assertTrue(outcome.detail.contains("no confirmation"))
    }

    @Test
    fun aRealSendReportsWhateverTheRadioSaid() {
        // Non-deterministic by nature -- it depends on the device's radio -- so this asserts
        // only that the channel reports an honest outcome and never throws. On an emulator
        // this is usually Delivered; with no service it is Failed("no service").
        val channel = SmsEscalationChannel(context)
        val outcome = escalate(channel, breach(EmergencyContact("Test", "+15555551234")))

        assertTrue(
            outcome is EscalationOutcome.Delivered ||
                outcome is EscalationOutcome.Unconfirmed ||
                outcome is EscalationOutcome.Failed,
            "unexpected outcome: $outcome",
        )
        println("radio reported: $outcome")
    }
}
