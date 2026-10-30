package com.halotap.app.escalation

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Android and Wear OS default: text every emergency contact, with no tap required.
 *
 * Automatic sending needs [Manifest.permission.SEND_SMS], which Google Play restricts. It is
 * grantable here under the published "Physical safety/emergency alerts to send SMS" exception,
 * declared through the Permissions Declaration Form. The no-permission alternative — an
 * ACTION_SENDTO intent — is useless for this path, because it needs the user to tap send and
 * a breach is precisely the moment they cannot.
 *
 * **This waits for the radio to say what happened.** `sendTextMessage` is asynchronous and
 * returns void: it throws only on malformed arguments, never when there is no signal, no SIM
 * or no service. Counting a call that did not throw as a delivery meant telling someone their
 * contacts had been alerted from a basement car park with no bars — which is exactly the
 * place this app is for. The `sentIntent` is the only thing that knows, so it is used.
 */
class SmsEscalationChannel(
    private val appContext: Context,
    private val resultTimeoutMillis: Long = DEFAULT_RESULT_TIMEOUT_MILLIS,
    override val id: String = ID,
) : EscalationChannel {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "halotap-sms").apply { isDaemon = true }
    }

    override suspend fun escalate(context: BreachContext): EscalationOutcome =
        suspendCoroutine { continuation ->
            // Off the caller's thread: this blocks waiting on the radio.
            executor.execute { continuation.resume(send(context)) }
        }

    private fun send(breach: BreachContext): EscalationOutcome {
        if (breach.contacts.isEmpty()) return EscalationOutcome.NotConfigured
        if (!hasPermission()) return EscalationOutcome.Failed("SEND_SMS not granted")

        val manager = smsManager() ?: return EscalationOutcome.Failed("no SmsManager available")

        val action = "$ACTION_SENT.${System.nanoTime()}"
        val results = ConcurrentHashMap<Int, Int>()
        val parts = breach.contacts.map { manager.divideMessage(breach.message) }
        val expected = parts.sumOf { it.size }
        val latch = CountDownLatch(expected)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results[intent.getIntExtra(EXTRA_TICKET, -1)] = resultCode
                latch.countDown()
            }
        }
        registerReceiver(receiver, action)

        val failures = mutableListOf<String>()
        var ticket = 0
        val ticketsByContact = breach.contacts.indices.associateWith { mutableListOf<Int>() }

        try {
            breach.contacts.forEachIndexed { index, contact ->
                val messageParts = parts[index]
                val intents = ArrayList<PendingIntent>(messageParts.size)
                repeat(messageParts.size) {
                    ticketsByContact.getValue(index) += ticket
                    intents += sentIntent(action, ticket)
                    ticket++
                }

                try {
                    if (messageParts.size > 1) {
                        manager.sendMultipartTextMessage(
                            contact.phoneNumber, null, messageParts, intents, null,
                        )
                    } else {
                        manager.sendTextMessage(
                            contact.phoneNumber, null, breach.message, intents.first(), null,
                        )
                    }
                } catch (failure: IllegalArgumentException) {
                    // A malformed number is the one thing that throws synchronously, and its
                    // tickets will never arrive, so they must not be waited on.
                    failures += "${contact.name}: ${failure.message ?: "unusable number"}"
                    repeat(messageParts.size) { latch.countDown() }
                }
            }

            val settled = latch.await(resultTimeoutMillis, TimeUnit.MILLISECONDS)
            return verdict(breach, ticketsByContact, results, failures, settled)
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    /**
     * What actually happened, per contact.
     *
     * A contact counts as reached only when every part of their message reported
     * [Activity.RESULT_OK]. One reached contact is a delivery; none reached but something
     * still outstanding is [EscalationOutcome.Unconfirmed], because the radio may yet send
     * it and claiming failure would be as wrong as claiming success.
     */
    private fun verdict(
        breach: BreachContext,
        ticketsByContact: Map<Int, List<Int>>,
        results: Map<Int, Int>,
        failures: MutableList<String>,
        settled: Boolean,
    ): EscalationOutcome {
        var delivered = 0
        var pending = 0

        ticketsByContact.forEach { (index, tickets) ->
            val name = breach.contacts[index].name
            val codes = tickets.map { results[it] }
            when {
                codes.any { it == null } -> pending++
                codes.all { it == Activity.RESULT_OK } -> delivered++
                else -> {
                    val error = codes.filterNotNull().first { it != Activity.RESULT_OK }
                    failures += "$name: ${error.describe()}"
                }
            }
        }

        return when {
            delivered > 0 -> EscalationOutcome.Delivered
            pending > 0 || !settled -> EscalationOutcome.Unconfirmed(
                "handed to the radio; no confirmation within ${resultTimeoutMillis}ms",
            )
            else -> EscalationOutcome.Failed(failures.joinToString("; ").ifEmpty { "send failed" })
        }
    }

    private fun Int.describe(): String = when (this) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        else -> "error $this"
    }

    private fun registerReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Required from API 33. Not exported: only this app's own PendingIntents fire it.
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    // Mutable because the telephony stack fills in the failure detail on some devices, and
    // the intent is explicit to this app's own package, so nothing else can reach it.
    private fun sentIntent(action: String, ticket: Int): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            ticket,
            Intent(action).setPackage(appContext.packageName).putExtra(EXTRA_TICKET, ticket),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    private fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun smsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    companion object {
        const val ID: String = "sms"

        /**
         * How long to wait for the radio before reporting it unconfirmed.
         *
         * Long enough for a normal send on a weak signal, short enough that the escalation
         * worker is not held open indefinitely when there is no service at all.
         */
        const val DEFAULT_RESULT_TIMEOUT_MILLIS: Long = 30_000

        private const val ACTION_SENT = "com.halotap.app.SMS_SENT"
        private const val EXTRA_TICKET = "ticket"
    }
}
