package com.halotap.app.wear.alarm

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which breach is open, and which has been dealt with.
 *
 * Three unrelated things read these two numbers -- the coordinator, the escalation worker and
 * the boot receiver -- and they are what stops one breach being escalated twice and what tells
 * the user the watch died mid-walk. The Apple Watch side has had `BreachTokensTests.swift`
 * since this existed; the Wear side had nothing.
 */
class BreachTokensTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferencesName = "halotap.breaches.tokenstest"

    private fun tokens() = BreachTokens(context, preferencesName)

    @BeforeTest
    @AfterTest
    fun clearPreferences() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun nothingIsOpenOnAFreshInstall() {
        assertEquals(0L, tokens().openToken())
        assertEquals(0L, tokens().handledToken())
    }

    @Test
    fun anOpenedBreachIsReadableByEveryoneElse() {
        tokens().open(token = 12_345)
        // A separate instance on purpose: the worker and the boot receiver each build their
        // own, in different processes, and must see what the coordinator wrote.
        assertEquals(12_345L, tokens().openToken())
    }

    @Test
    fun handlingABreachDoesNotCloseIt() {
        // The alert screen still needs an open token to match the worker's summary against,
        // and the boot receiver distinguishes the two to tell a power loss from a disarm.
        tokens().open(token = 500)
        tokens().markHandled(token = 500)

        assertEquals(500L, tokens().openToken())
        assertEquals(500L, tokens().handledToken())
    }

    @Test
    fun disarmingClosesTheBreachWithoutMarkingItHandled() {
        // Without the clear, the next boot would read an open token with nothing handled and
        // announce a power loss after an ordinary disarm.
        tokens().open(token = 700)
        tokens().clearOpen()

        assertEquals(0L, tokens().openToken())
        assertEquals(0L, tokens().handledToken())
    }

    @Test
    fun aNewBreachReplacesTheOpenOneAndLeavesTheHandledOneAlone() {
        tokens().open(token = 100)
        tokens().markHandled(token = 100)
        tokens().open(token = 200)

        assertEquals(200L, tokens().openToken())
        assertEquals(
            100L,
            tokens().handledToken(),
            "the previous breach stays handled, or its finished work would be re-sent",
        )
    }

    @Test
    fun theStoreIsIsolatedFromTheAppsRealOne() {
        // Guards the test itself: a default-named store would have these cases trampling the
        // state a real session left behind on the device.
        tokens().open(token = 999)
        assertEquals(0L, BreachTokens(context, "halotap.breaches.someoneelse").openToken())
    }
}
