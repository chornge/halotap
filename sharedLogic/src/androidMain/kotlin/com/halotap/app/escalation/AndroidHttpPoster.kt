package com.halotap.app.escalation

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * HttpURLConnection on a background thread.
 *
 * Hand-rolled rather than pulling in a client, so :sharedLogic keeps its current property of
 * having no third-party dependencies at all — worth something in an app people are asked to
 * trust with an emergency path. The single-thread executor is enough: escalation posts are
 * rare and a breach sends at most a handful.
 */
class AndroidHttpPoster(
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : HttpPoster {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "halotap-escalation").apply { isDaemon = true }
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpPostResult = suspendCoroutine { continuation ->
        executor.execute {
            continuation.resume(send(url, headers, body))
        }
    }

    // Any transport failure is a reportable outcome rather than a crash, so the catch is
    // deliberately broad.
    @Suppress("TooGenericExceptionCaught")
    private fun send(url: String, headers: Map<String, String>, body: String): HttpPostResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                doOutput = true
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection.outputStream.use { it.write(body.encodeToByteArray()) }

            val status = connection.responseCode
            // Error responses come from errorStream, not inputStream; reading the wrong one
            // throws and would otherwise be reported as a transport failure.
            val stream =
                if (status in HTTP_SUCCESS) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpPostResult.Response(status, text)
        } catch (failure: Exception) {
            HttpPostResult.TransportFailure(failure.message ?: "request failed")
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Int = 10_000
    }
}
