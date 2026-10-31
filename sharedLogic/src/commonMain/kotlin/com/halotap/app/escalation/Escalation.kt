package com.halotap.app.escalation

import com.halotap.app.BreachReason
import kotlin.coroutines.cancellation.CancellationException

/** Someone to reach when a session breaches. */
data class EmergencyContact(
    val name: String,
    val phoneNumber: String,
) {
    init {
        require(name.isNotBlank()) { "An emergency contact needs a name" }
        require(phoneNumber.isNotBlank()) { "An emergency contact needs a phone number" }
    }
}

/**
 * The text sent to contacts.
 *
 * Shared so both watches say the same thing: a recipient should not be able to tell which
 * device raised the alarm, and wording this blunt is easier to get right once.
 */
object BreachMessages {
    fun default(reason: BreachReason): String = when (reason) {
        BreachReason.WRONG_SECRET ->
            "Halotap alert: the disarm code was entered incorrectly. This may be a real emergency."
        BreachReason.DEADLINE_PASSED ->
            "Halotap alert: no disarm code was entered in time. This may be a real emergency."
        BreachReason.DURESS ->
            "Halotap alert: the duress code was entered. They are being forced to disarm and " +
                "cannot speak freely. Treat this as an emergency in progress."
    }
}

/** Everything a channel needs to raise the alarm. */
data class BreachContext(
    val reason: BreachReason,
    /**
     * When the breach happened, in **milliseconds since the Unix epoch**.
     *
     * The one timestamp in this app that is wall-clock rather than monotonic, and the contract
     * is spelled out because it was once broken. Every deadline is monotonic so that changing
     * the watch's time cannot move it — but this value leaves the device, in the webhook body
     * and in a user's own alert log, where "milliseconds since this watch last booted" means
     * nothing to the reader and cannot be compared against anything. Whoever receives a breach
     * needs to know what time it was.
     *
     * Nothing may be *decided* from this. It is for reporting only.
     */
    val occurredAtMillis: Long,
    val contacts: List<EmergencyContact>,
    val message: String,
)

/** What happened when one channel tried. */
sealed interface EscalationOutcome {
    /** The channel confirmed the alert went out. */
    data object Delivered : EscalationOutcome

    /**
     * Handed to the system, which reported no result.
     *
     * Not the same as [Delivered] and deliberately not folded into it. Placing a call on
     * watchOS goes through `openSystemURL`, which returns nothing at all — so reporting
     * success there would be a guess, and telling someone their emergency contacts were
     * alerted when that is unknown is the worst lie this app could tell.
     */
    data class Unconfirmed(val detail: String) : EscalationOutcome

    /** The channel is present but the user never set it up. Not a failure. */
    data object NotConfigured : EscalationOutcome

    data class Failed(val reason: String) : EscalationOutcome
}

/** One way of raising the alarm. */
interface EscalationChannel {
    /** Stable identifier, used in reporting and to let a user disable one channel. */
    val id: String

    suspend fun escalate(context: BreachContext): EscalationOutcome
}

/** What one channel did, paired with which channel did it. */
data class EscalationAttempt(
    val channelId: String,
    val outcome: EscalationOutcome,
)

/** How hard to try. */
enum class EscalationPolicy {
    /**
     * Run every channel, even after one succeeds. The default, because a breach is
     * exactly when redundancy is worth more than tidiness: SMS may be queued behind no
     * signal while a webhook goes straight out, and neither one knows about the other.
     */
    ALL_CHANNELS,

    /** Stop at the first delivery. For users who do not want duplicate alerts. */
    FIRST_SUCCESS,
}

/**
 * Runs the configured channels when a session breaches.
 *
 * Built so that the platform's own channel is always present and a user-supplied one is
 * *added* rather than swapping it out — losing the built-in alarm because a webhook was
 * misconfigured is the one failure this must not have.
 *
 * A channel that throws is recorded as [EscalationOutcome.Failed] and the rest still run.
 * One bad channel cannot take the alarm down with it.
 */
class Escalator(
    private val channels: List<EscalationChannel>,
    private val policy: EscalationPolicy = EscalationPolicy.ALL_CHANNELS,
) {
    suspend fun escalate(context: BreachContext): List<EscalationAttempt> {
        val attempts = mutableListOf<EscalationAttempt>()
        for (channel in channels) {
            @Suppress("TooGenericExceptionCaught") // A channel must never take the alarm down.
            val outcome = try {
                channel.escalate(context)
            } catch (cancellation: CancellationException) {
                // Not a channel failure, and not this function's to absorb. Recording it as
                // Failed would report the remaining channels as having been tried when the
                // escalation was actually abandoned, and would leave the coroutine running
                // after its caller asked it to stop.
                throw cancellation
            } catch (failure: Throwable) {
                EscalationOutcome.Failed(failure.message ?: failure::class.simpleName ?: "unknown")
            }
            attempts += EscalationAttempt(channel.id, outcome)
            if (policy == EscalationPolicy.FIRST_SUCCESS && outcome is EscalationOutcome.Delivered) {
                break
            }
        }
        return attempts
    }

    /** True only when a channel *confirmed* delivery. [EscalationOutcome.Unconfirmed] does not count. */
    fun succeeded(attempts: List<EscalationAttempt>): Boolean =
        attempts.any { it.outcome is EscalationOutcome.Delivered }

    /** True when something was handed off, confirmed or not. Weaker than [succeeded]. */
    fun attempted(attempts: List<EscalationAttempt>): Boolean =
        attempts.any {
            it.outcome is EscalationOutcome.Delivered || it.outcome is EscalationOutcome.Unconfirmed
        }
}
