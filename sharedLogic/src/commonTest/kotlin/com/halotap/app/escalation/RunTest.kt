package com.halotap.app.escalation

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs a suspending test body without pulling kotlinx-coroutines into a module that
 * otherwise has no dependencies at all.
 *
 * Every channel under test completes without really suspending, so driving the coroutine
 * to completion needs nothing more than the stdlib. The [check] makes that assumption
 * loud: if a test ever does suspend for real, this fails rather than passing vacuously.
 */
internal fun runTest(block: suspend () -> Unit) {
    var completed = false
    var failure: Throwable? = null

    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result ->
            completed = true
            failure = result.exceptionOrNull()
        },
    )

    check(completed) {
        "The test body actually suspended; this runner only drives bodies that complete eagerly."
    }
    failure?.let { throw it }
}
