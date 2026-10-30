package com.halotap.app.escalation

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * NSURLSession, bridged to a suspending call.
 *
 * NSURLSession is already asynchronous, so unlike the Android side this needs no thread of its
 * own: its completion handler maps straight onto a continuation.
 */
@OptIn(ExperimentalForeignApi::class)
class DarwinHttpPoster(
    private val session: NSURLSession = NSURLSession.sharedSession,
) : HttpPoster {

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpPostResult = suspendCoroutine { continuation ->
        val target = NSURL.URLWithString(url)
        if (target == null) {
            continuation.resume(HttpPostResult.TransportFailure("malformed URL"))
            return@suspendCoroutine
        }

        val request = NSMutableURLRequest(uRL = target).apply {
            setHTTPMethod("POST")
            headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
            setHTTPBody(body.toNSData())
        }

        session.dataTaskWithRequest(request) { data, response, error ->
            // Resumed exactly once on every path; NSURLSession calls this handler once.
            when {
                error != null ->
                    continuation.resume(
                        HttpPostResult.TransportFailure(error.localizedDescription),
                    )

                else -> {
                    val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    continuation.resume(HttpPostResult.Response(status, data.decodeToString()))
                }
            }
        }.resume()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun String.toNSData(): NSData? =
    NSString.create(string = this).dataUsingEncoding(NSUTF8StringEncoding)

@OptIn(ExperimentalForeignApi::class)
private fun NSData?.decodeToString(): String =
    this?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty()
