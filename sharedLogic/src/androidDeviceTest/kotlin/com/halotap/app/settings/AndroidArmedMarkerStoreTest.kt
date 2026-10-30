package com.halotap.app.settings

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.halotap.app.StandbySession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The marker that lets an interrupted hold survive the app being torn down by a call.
 *
 * On a device rather than the JVM because it is SharedPreferences, and because the thing worth
 * proving is that the write has actually landed by the time the process could die.
 */
class AndroidArmedMarkerStoreTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferencesName = "halotap.session.test"

    private fun store() = AndroidArmedMarkerStore(context, preferencesName)

    @BeforeTest
    @AfterTest
    fun clearPreferences() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun noMarkerByDefault() {
        assertNull(store().load())
    }

    @Test
    fun theMarkerRoundTrips() {
        store().save(sinceMillis = 1_234_567)
        assertEquals(1_234_567, store().load())
    }

    @Test
    fun zeroIsAMarkerNotAnAbsence() {
        // getLong's default is 0, so a naive implementation cannot tell "armed at zero" from
        // "never armed". Only a boot-instant hold would hit it, but the ambiguity is real.
        store().save(sinceMillis = 0)
        assertEquals(0, store().load())
    }

    @Test
    fun savingNullClearsIt() {
        store().save(sinceMillis = 99)
        store().save(sinceMillis = null)
        assertNull(store().load())
    }

    @Test
    fun theWriteIsVisibleImmediatelyToAnotherInstance() {
        // The reason this uses commit() rather than apply(). An incoming call can tear the
        // process down in the window apply() leaves open, which is exactly the write that
        // must not be lost.
        store().save(sinceMillis = 4_242)
        assertEquals(4_242, AndroidArmedMarkerStore(context, preferencesName).load())
    }

    @Test
    fun aFreshMarkerIsRestorableAgainstTheRealClock() {
        val now = SystemClock.elapsedRealtime()
        store().save(sinceMillis = now)

        val saved = store().load()!!
        assertTrue(StandbySession.isArmedMarkerUsable(saved, SystemClock.elapsedRealtime()))
    }

    @Test
    fun aMarkerFromBeforeARebootIsRejected() {
        // elapsedRealtime resets on reboot, so a value ahead of now means the device restarted.
        store().save(sinceMillis = SystemClock.elapsedRealtime() + 60_000)

        val saved = store().load()!!
        assertFalse(StandbySession.isArmedMarkerUsable(saved, SystemClock.elapsedRealtime()))
    }
}
