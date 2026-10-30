package com.halotap.app.escalation

/** Statuses that mean the request was accepted. Shared so the platform posters agree. */
internal val HTTP_SUCCESS: IntRange = 200..299

/** A minimal HTTP POST, implemented per platform. */
interface HttpPoster {
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpPostResult
}

sealed interface HttpPostResult {
    data class Response(val statusCode: Int, val body: String) : HttpPostResult
    data class TransportFailure(val reason: String) : HttpPostResult
}

/**
 * Where a user's own escalation should go.
 *
 * Two shapes rather than one free-form request, because they are the two things people
 * actually have: a endpoint they control, or a Twilio account.
 */
sealed interface HttpEscalationConfig {

    /** POST a JSON description of the breach to an endpoint the user controls. */
    data class Webhook(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : HttpEscalationConfig {
        init {
            require(url.startsWith("https://")) { "A webhook URL must be https" }
        }
    }

    /** Send one SMS per contact through the user's own Twilio account. */
    data class Twilio(
        val accountSid: String,
        val authToken: String,
        val fromNumber: String,
    ) : HttpEscalationConfig {
        init {
            require(accountSid.isNotBlank()) { "Twilio needs an account SID" }
            require(authToken.isNotBlank()) { "Twilio needs an auth token" }
            require(fromNumber.isNotBlank()) { "Twilio needs a from number" }
        }

        val messagesUrl: String
            get() = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json"
    }
}

/**
 * The bring-your-own channel.
 *
 * This is additive: it runs alongside the platform's own channel rather than replacing it,
 * so a typo in a webhook URL costs a redundant alert, never the alarm itself. With no
 * [config] it reports [EscalationOutcome.NotConfigured], which the [Escalator] treats as a
 * non-event.
 */
class HttpEscalationChannel(
    private val poster: HttpPoster,
    private val config: HttpEscalationConfig? = null,
    override val id: String = ID,
) : EscalationChannel {

    override suspend fun escalate(context: BreachContext): EscalationOutcome =
        when (config) {
            null -> EscalationOutcome.NotConfigured
            is HttpEscalationConfig.Webhook -> postWebhook(config, context)
            is HttpEscalationConfig.Twilio -> postTwilio(config, context)
        }

    private suspend fun postWebhook(
        config: HttpEscalationConfig.Webhook,
        context: BreachContext,
    ): EscalationOutcome {
        val result = poster.post(
            url = config.url,
            headers = config.headers + ("Content-Type" to "application/json"),
            body = breachJson(context),
        )
        return result.toOutcome()
    }

    /**
     * One request per contact, because Twilio's Messages endpoint sends to a single
     * recipient. Every contact is attempted even after one fails: a breach should not stop
     * alerting the second person because the first number was mistyped.
     */
    private suspend fun postTwilio(
        config: HttpEscalationConfig.Twilio,
        context: BreachContext,
    ): EscalationOutcome {
        if (context.contacts.isEmpty()) return EscalationOutcome.NotConfigured

        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Authorization" to "Basic " + base64("${config.accountSid}:${config.authToken}"),
        )
        val failures = mutableListOf<String>()
        var delivered = 0

        for (contact in context.contacts) {
            val body = listOf(
                "To" to contact.phoneNumber,
                "From" to config.fromNumber,
                "Body" to context.message,
            ).joinToString("&") { (key, value) -> "$key=${formEncode(value)}" }

            when (val outcome = poster.post(config.messagesUrl, headers, body).toOutcome()) {
                is EscalationOutcome.Delivered -> delivered++
                is EscalationOutcome.Failed -> failures += "${contact.name}: ${outcome.reason}"
                // Neither can arise from an HTTP response, which is always one of the three
                // above; present because the `when` is exhaustive over the sealed type.
                is EscalationOutcome.NotConfigured, is EscalationOutcome.Unconfirmed -> Unit
            }
        }

        return when {
            delivered > 0 -> EscalationOutcome.Delivered
            else -> EscalationOutcome.Failed(failures.joinToString("; ").ifEmpty { "no contacts reached" })
        }
    }

    private fun HttpPostResult.toOutcome(): EscalationOutcome = when (this) {
        is HttpPostResult.TransportFailure -> EscalationOutcome.Failed(reason)
        is HttpPostResult.Response ->
            if (statusCode in HTTP_SUCCESS) {
                EscalationOutcome.Delivered
            } else {
                EscalationOutcome.Failed("HTTP $statusCode: ${body.take(n = 200)}")
            }
    }

    companion object {
        const val ID: String = "http"
    }
}

internal fun breachJson(context: BreachContext): String {
    val contacts = context.contacts.joinToString(",") { contact ->
        """{"name":${jsonString(contact.name)},"phoneNumber":${jsonString(contact.phoneNumber)}}"""
    }
    return """{"reason":"${context.reason.name}","occurredAtMillis":${context.occurredAtMillis},""" +
        """"message":${jsonString(context.message)},"contacts":[$contacts]}"""
}

@Suppress("MagicNumber") // Bit offsets and masks; naming them would obscure the algorithm.
internal fun jsonString(value: String): String {
    val escaped = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character < ' ') {
                        append("\\u").append(character.code.toString(radix = 16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }
    return escaped
}

@Suppress("MagicNumber") // Bit offsets and masks; naming them would obscure the algorithm.
internal fun formEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val character = byte.toInt().toChar()
        when {
            character.isLetterOrDigit() && character.code < 128 -> append(character)
            character in "-._~" -> append(character)
            else -> append('%').append(
                (byte.toInt() and 0xFF).toString(radix = 16).uppercase().padStart(2, '0'),
            )
        }
    }
}

/** Base64, written out rather than pulled in, to keep this module dependency-free. */
@Suppress("MagicNumber") // Bit offsets and masks; naming them would obscure the algorithm.
internal fun base64(value: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val bytes = value.encodeToByteArray()
    return buildString {
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2

            append(alphabet[(triple shr 18) and 0x3F])
            append(alphabet[(triple shr 12) and 0x3F])
            append(if (index + 1 < bytes.size) alphabet[(triple shr 6) and 0x3F] else '=')
            append(if (index + 2 < bytes.size) alphabet[triple and 0x3F] else '=')
            index += 3
        }
    }
}
