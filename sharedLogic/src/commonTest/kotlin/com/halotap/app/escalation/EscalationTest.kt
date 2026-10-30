package com.halotap.app.escalation

import com.halotap.app.BreachReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val contacts = listOf(
    EmergencyContact(name = "Ada", phoneNumber = "+15550001"),
    EmergencyContact(name = "Grace", phoneNumber = "+15550002"),
)

private val breach = BreachContext(
    reason = BreachReason.DEADLINE_PASSED,
    occurredAtMillis = 1_700_000_000_000,
    contacts = contacts,
    message = "Halotap: no disarm. Last known state \"armed\".",
)

private class FakeChannel(
    override val id: String,
    private val outcome: EscalationOutcome? = EscalationOutcome.Delivered,
) : EscalationChannel {
    var calls = 0
        private set

    override suspend fun escalate(context: BreachContext): EscalationOutcome {
        calls++
        return outcome ?: error("channel exploded")
    }
}

private class FakePoster(
    private val result: HttpPostResult = HttpPostResult.Response(204, ""),
) : HttpPoster {
    val requests = mutableListOf<Triple<String, Map<String, String>, String>>()

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpPostResult {
        requests += Triple(url, headers, body)
        return result
    }
}

class EscalatorTest {

    @Test
    fun everyChannelRunsByDefault() = runTest {
        val platform = FakeChannel("sms")
        val byo = FakeChannel("http")
        val attempts = Escalator(listOf(platform, byo)).escalate(breach)

        assertEquals(1, platform.calls)
        assertEquals(1, byo.calls)
        assertEquals(listOf("sms", "http"), attempts.map { it.channelId })
    }

    @Test
    fun firstSuccessStopsEarly() = runTest {
        val platform = FakeChannel("sms")
        val byo = FakeChannel("http")
        Escalator(listOf(platform, byo), EscalationPolicy.FIRST_SUCCESS).escalate(breach)

        assertEquals(1, platform.calls)
        assertEquals(0, byo.calls)
    }

    @Test
    fun aThrowingChannelCannotTakeTheAlarmDown() = runTest {
        // The whole point of the seam: a misconfigured bring-your-own channel must not
        // cost the platform alarm that was going to work.
        val exploding = FakeChannel("http", outcome = null)
        val platform = FakeChannel("sms")

        val attempts = Escalator(listOf(exploding, platform)).escalate(breach)

        assertEquals(1, platform.calls)
        assertTrue(attempts.first().outcome is EscalationOutcome.Failed)
        assertEquals(EscalationOutcome.Delivered, attempts.last().outcome)
    }

    @Test
    fun succeededIgnoresFailuresAndUnconfiguredChannels() = runTest {
        val escalator = Escalator(
            listOf(
                FakeChannel("http", EscalationOutcome.NotConfigured),
                FakeChannel("sms", EscalationOutcome.Failed("no signal")),
            ),
        )
        assertFalse(escalator.succeeded(escalator.escalate(breach)))

        val withOneGood = Escalator(
            listOf(
                FakeChannel("http", EscalationOutcome.NotConfigured),
                FakeChannel("sms"),
            ),
        )
        assertTrue(withOneGood.succeeded(withOneGood.escalate(breach)))
    }
}

class HttpEscalationChannelTest {

    @Test
    fun anUnconfiguredChannelIsNotAFailure() = runTest {
        val channel = HttpEscalationChannel(FakePoster(), config = null)
        assertEquals(EscalationOutcome.NotConfigured, channel.escalate(breach))
    }

    @Test
    fun webhookPostsJsonDescribingTheBreach() = runTest {
        val poster = FakePoster()
        val channel = HttpEscalationChannel(
            poster,
            HttpEscalationConfig.Webhook("https://example.test/hook", mapOf("X-Token" to "abc")),
        )

        assertEquals(EscalationOutcome.Delivered, channel.escalate(breach))

        val (url, headers, body) = poster.requests.single()
        assertEquals("https://example.test/hook", url)
        assertEquals("abc", headers["X-Token"])
        assertEquals("application/json", headers["Content-Type"])
        assertTrue(body.contains(""""reason":"DEADLINE_PASSED""""))
        assertTrue(body.contains(""""phoneNumber":"+15550002""""))
        // The message contains quotes, so this also proves the escaping holds.
        assertTrue(body.contains("""\"armed\""""))
    }

    @Test
    fun twilioSendsOncePerContactWithBasicAuth() = runTest {
        val poster = FakePoster(HttpPostResult.Response(201, "{}"))
        val channel = HttpEscalationChannel(
            poster,
            HttpEscalationConfig.Twilio("AC123", "secret", "+15559999"),
        )

        assertEquals(EscalationOutcome.Delivered, channel.escalate(breach))
        assertEquals(2, poster.requests.size)

        val (url, headers, body) = poster.requests.first()
        assertEquals("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json", url)
        assertEquals("Basic ${base64("AC123:secret")}", headers["Authorization"])
        assertTrue(body.startsWith("To=%2B15550001"))
        assertTrue(body.contains("From=%2B15559999"))
    }

    @Test
    fun oneBadNumberDoesNotStopTheOthers() = runTest {
        // Alternating failure would be better, but a single poster result is enough to
        // show every contact is attempted rather than the loop breaking on the first.
        val poster = FakePoster(HttpPostResult.Response(400, "bad number"))
        val channel = HttpEscalationChannel(
            poster,
            HttpEscalationConfig.Twilio("AC123", "secret", "+15559999"),
        )

        val outcome = channel.escalate(breach)
        assertEquals(2, poster.requests.size)
        assertIs<EscalationOutcome.Failed>(outcome)
        assertTrue(outcome.reason.contains("Ada") && outcome.reason.contains("Grace"))
    }

    @Test
    fun transportFailuresAreReportedNotThrown() = runTest {
        val poster = FakePoster(HttpPostResult.TransportFailure("offline"))
        val channel = HttpEscalationChannel(poster, HttpEscalationConfig.Webhook("https://a.test/h"))

        assertEquals(EscalationOutcome.Failed("offline"), channel.escalate(breach))
    }

    @Test
    fun configurationIsValidatedUpFront() {
        assertFailsWith<IllegalArgumentException> { HttpEscalationConfig.Webhook("http://insecure.test") }
        assertFailsWith<IllegalArgumentException> { HttpEscalationConfig.Twilio("", "t", "+1") }
        assertFailsWith<IllegalArgumentException> { EmergencyContact(name = " ", phoneNumber = "+1") }
    }
}

class EncodingTest {

    @Test
    fun base64MatchesKnownVectors() {
        assertEquals("", base64(""))
        assertEquals("Zg==", base64("f"))
        assertEquals("Zm8=", base64("fo"))
        assertEquals("Zm9v", base64("foo"))
        assertEquals("Zm9vYmFy", base64("foobar"))
    }

    @Test
    fun formEncodingEscapesWhatItMust() {
        assertEquals("%2B15550001", formEncode("+15550001"))
        assertEquals("a%20b", formEncode("a b"))
        assertEquals("safe-._~", formEncode("safe-._~"))
        // Multi-byte input must be percent-encoded per UTF-8 byte, not per character.
        assertEquals("%C3%A9", formEncode("é"))
    }
}

class UnconfirmedOutcomeTest {

    @Test
    fun unconfirmedIsNotSuccess() {
        // The Apple Watch call channel ends here on every breach, because openSystemURL
        // reports nothing back. Counting it as delivery would tell a user their emergency
        // contacts were alerted when nobody knows whether they were.
        val escalator = Escalator(listOf(FakeChannel("call", EscalationOutcome.Unconfirmed("dialling Ada"))))
        val attempts = escalator.escalate2(breach)

        assertFalse(escalator.succeeded(attempts))
        assertTrue(escalator.attempted(attempts))
    }

    @Test
    fun aConfirmedChannelOutranksAnUnconfirmedOne() {
        val escalator = Escalator(
            listOf(
                FakeChannel("call", EscalationOutcome.Unconfirmed("dialling Ada")),
                FakeChannel("http"),
            ),
        )
        val attempts = escalator.escalate2(breach)

        assertTrue(escalator.succeeded(attempts))
        assertTrue(escalator.attempted(attempts))
    }

    @Test
    fun nothingAttemptedWhenEveryChannelIsUnconfiguredOrFailed() {
        val escalator = Escalator(
            listOf(
                FakeChannel("http", EscalationOutcome.NotConfigured),
                FakeChannel("call", EscalationOutcome.Failed("a call is already in progress")),
            ),
        )
        val attempts = escalator.escalate2(breach)

        assertFalse(escalator.succeeded(attempts))
        assertFalse(escalator.attempted(attempts))
    }
}

/** Shorthand so these tests read without a runTest wrapper on every line. */
private fun Escalator.escalate2(context: BreachContext): List<EscalationAttempt> {
    var result: List<EscalationAttempt> = emptyList()
    runTest { result = escalate(context) }
    return result
}
