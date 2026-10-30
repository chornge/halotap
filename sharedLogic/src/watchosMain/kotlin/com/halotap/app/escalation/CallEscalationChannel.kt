package com.halotap.app.escalation

import platform.CallKit.CXCall
import platform.CallKit.CXCallObserver
import platform.Foundation.NSURL
import platform.WatchKit.WKApplication

/**
 * The Apple Watch default: place a call to the first reachable contact.
 *
 * It is a call rather than a text because watchOS cannot send one. MessageUI is simply not
 * part of the watchOS SDK, and even by way of a companion iPhone, MFMessageComposeViewController
 * requires the user to tap Send — which a breach is exactly the wrong moment to depend on.
 * Dialling is the only alarm watchOS will raise without a server.
 *
 * Only the first contact is called, because a phone can only be in one call at a time. Reaching
 * everyone else is the bring-your-own [HttpEscalationChannel]'s job, which is the main reason
 * configuring one matters more on Apple Watch than on Wear OS.
 */
class CallEscalationChannel(
    private val callObserver: CXCallObserver = CXCallObserver(),
    override val id: String = ID,
) : EscalationChannel {

    override suspend fun escalate(context: BreachContext): EscalationOutcome {
        val contact = context.contacts.firstOrNull() ?: return EscalationOutcome.NotConfigured

        // A second call cannot be placed while one is live: the dial would either do nothing
        // or interrupt the call already in progress, which might be the user talking to
        // someone who can help. Say so plainly instead, so the caller can fall back to a
        // bring-your-own channel rather than believing an alarm went out.
        if (hasLiveCall()) {
            return EscalationOutcome.Failed(
                "a call is already in progress, so ${contact.name} could not be dialled",
            )
        }

        val digits = contact.phoneNumber.filter { it.isDigit() || it == '+' }
        val url = NSURL.URLWithString("tel://$digits")
            ?: return EscalationOutcome.Failed("${contact.name}: unusable phone number")

        @Suppress("TooGenericExceptionCaught") // Dialling must not crash the breach path.
        return try {
            WKApplication.sharedApplication().openSystemURL(url)
            // Unconfirmed, not Delivered. openSystemURL returns Void: it reports neither
            // success nor failure, so there is nothing here to justify claiming the contact
            // was reached.
            EscalationOutcome.Unconfirmed("dialling ${contact.name}")
        } catch (failure: Throwable) {
            EscalationOutcome.Failed(failure.message ?: "could not place the call")
        }
    }

    private fun hasLiveCall(): Boolean =
        callObserver.calls.any { call -> (call as? CXCall)?.hasEnded == false }

    companion object {
        const val ID: String = "call"
    }
}
