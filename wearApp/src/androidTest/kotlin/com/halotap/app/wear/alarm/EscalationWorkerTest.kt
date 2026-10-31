package com.halotap.app.wear.alarm

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.test.platform.app.InstrumentationRegistry
import com.halotap.app.BreachReason
import com.halotap.app.escalation.HttpEscalationConfig
import com.halotap.app.settings.AlertRecord
import com.halotap.app.settings.InMemoryArmedMarkerStore
import com.halotap.app.settings.InMemorySettingsStore
import com.halotap.app.settings.HalotapSettings
import com.halotap.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The worker that actually sends the alert.
 *
 * This is the whole durability claim -- "on Wear OS the alert survives the app being killed,
 * because an exact alarm hands the sending to a worker" -- and it had no test of any kind.
 * Driven through [TestListenableWorkerBuilder] rather than through WorkManager's scheduler, so
 * each case runs `doWork` directly and nothing depends on when the system decides to schedule.
 *
 * The stores are the in-memory ones: a device run must not touch the PIN and contacts of
 * whoever is wearing the watch.
 *
 * No case here lets an SMS out. `SEND_SMS` is not granted to the test, and every case that
 * needs a failing channel uses an unroutable webhook instead, so nothing is ever sent to a
 * real number.
 */
class EscalationWorkerTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tokenPreferences = "halotap.breaches.workertest"

    private lateinit var settings: InMemorySettingsStore
    private lateinit var markers: InMemoryArmedMarkerStore

    /** A webhook nothing is listening on, so the HTTP channel fails fast and locally. */
    private val deadWebhook = HttpEscalationConfig.Webhook(url = "https://127.0.0.1:1/halotap")

    private fun tokens() = BreachTokens(context, tokenPreferences)

    @BeforeTest
    fun setUp() {
        settings = InMemorySettingsStore()
        markers = InMemoryArmedMarkerStore()
        clearTokens()
    }

    @AfterTest
    fun tearDown() = clearTokens()

    private fun clearTokens() {
        context.getSharedPreferences(tokenPreferences, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun runWorker(
        token: Long,
        reason: BreachReason = BreachReason.DEADLINE_PASSED,
        runAttemptCount: Int = 0,
        store: SettingsStore = settings,
    ): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<EscalationWorker>(context)
            .setInputData(
                workDataOf(
                    EscalationWorker.KEY_REASON to reason.name,
                    EscalationWorker.KEY_TOKEN to token,
                ),
            )
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = EscalationWorker(
                        context = appContext,
                        parameters = workerParameters,
                        settings = store,
                        tokens = tokens(),
                        markers = markers,
                    )
                },
            )
            .build()

        return runBlocking { worker.doWork() }
    }

    // MARK: - Deduplication

    @Test
    fun aBreachAlreadyHandledIsNotSentAgain() {
        // The case the token exists for. KEEP only deduplicates against *pending* work, so
        // once the first run has finished, the other path's enqueue starts a second run --
        // and before the token, that second run texted everybody again.
        val sentinel = AlertRecord(
            occurredAtMillis = 1_700_000_000_000,
            reason = BreachReason.DURESS,
            detail = "the first run's result",
        )
        settings.saveLastAlert(sentinel)
        tokens().open(token = 4_242)
        tokens().markHandled(token = 4_242)

        val result = runWorker(token = 4_242)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            sentinel.detail,
            settings.loadLastAlert()?.detail,
            "the skipped run must not overwrite the record of the run that actually sent",
        )
    }

    @Test
    fun aDifferentBreachIsNotDeduplicatedAgainstTheLastOne() {
        tokens().markHandled(token = 1_000)

        runWorker(token = 2_000)

        assertNotNull(
            settings.loadLastAlert(),
            "a new breach must escalate even though an older one was handled",
        )
        assertEquals(2_000L, tokens().handledToken())
    }

    // MARK: - Effects of a run

    @Test
    fun aRunRecordsTheAlertAndMarksTheBreachHandled() {
        val result = runWorker(token = 7_000, reason = BreachReason.DURESS)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(7_000L, tokens().handledToken())

        val record = assertNotNull(settings.loadLastAlert())
        assertEquals(BreachReason.DURESS, record.reason)
        // Nothing is set up, so both channels report it rather than failing.
        assertTrue(record.detail.contains("not-configured"), "unexpected summary: ${record.detail}")
    }

    @Test
    fun theAlertIsRecordedEvenWhenNothingIsConfigured() {
        // Afterwards the user needs to know an alert was raised and how it went. "Nobody was
        // reachable" is exactly the outcome they most need to see.
        runWorker(token = 8_000)

        assertNotNull(settings.loadLastAlert())
    }

    @Test
    fun theArmedMarkerIsDroppedSoTheAppDoesNotRearmItself() {
        markers.save(sinceMillis = 50_000)

        runWorker(token = 9_000)

        assertNull(
            markers.load(),
            "a marker left behind would put the app back on watch on its next launch",
        )
    }

    @Suppress("RestrictedApi") // Result.Success.outputData is library-group in androidx.work.
    @Test
    fun theTokenTravelsBackOutWithTheSummary() {
        // The alert screen matches on it, so a finished run for an older breach is not read
        // as this one's result.
        val result = runWorker(token = 11_000)

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(11_000L, output.getLong(EscalationWorker.KEY_TOKEN, 0L))
        assertNotNull(output.getString(EscalationWorker.KEY_SUMMARY))
    }

    // MARK: - The retry ladder

    @Test
    fun aFailedSendIsRetried() {
        settings = InMemorySettingsStore(HalotapSettings(http = deadWebhook))

        val result = runWorker(token = 12_000, store = settings)

        assertTrue(result is ListenableWorker.Result.Retry, "expected a retry, got $result")
    }

    @Test
    fun aRetryIsNotDeduplicatedAgainstItself() {
        // The bug this pins down: the token used to be marked handled before the retry was
        // returned, so the retry hit the dedup check, reported success and sent nothing. The
        // entire ladder was unreachable and a breach that failed on no signal was dropped.
        settings = InMemorySettingsStore(HalotapSettings(http = deadWebhook))

        runWorker(token = 13_000, store = settings)

        assertEquals(
            0L,
            tokens().handledToken(),
            "a breach still owed a retry must not be recorded as handled",
        )
    }

    @Test
    fun theLadderStopsOnceTheRetriesAreSpent() {
        settings = InMemorySettingsStore(HalotapSettings(http = deadWebhook))

        val result = runWorker(token = 14_000, runAttemptCount = 99, store = settings)

        assertTrue(result is ListenableWorker.Result.Failure, "expected a failure, got $result")
        assertEquals(
            14_000L,
            tokens().handledToken(),
            "a breach with nothing left to try is handled, however badly it went",
        )
    }

    @Test
    fun nothingConfiguredIsNotRetriedForever() {
        // A user who has not finished setup, not a transport failure. Retrying would drain
        // the battery for nobody's benefit.
        val result = runWorker(token = 15_000)

        assertTrue(result is ListenableWorker.Result.Success, "expected success, got $result")
    }
}
