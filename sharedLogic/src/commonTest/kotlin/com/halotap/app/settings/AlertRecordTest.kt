package com.halotap.app.settings

import com.halotap.app.BreachReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlertRecordTest {

    private val record = AlertRecord(
        occurredAtMillis = 1_761_800_000_000,
        reason = BreachReason.DURESS,
        detail = "Contacts alerted via sms",
    )

    @Test
    fun aRecordSurvivesTheRoundTrip() {
        assertEquals(record, AlertRecords.decode(AlertRecords.encode(record)))
    }

    @Test
    fun aDetailContainingTheSeparatorSurvives() {
        // The worker's own summary is "sms=delivered, http=not-configured" today, but it is
        // free text from the escalation layer and must not be able to corrupt the record.
        val awkward = record.copy(detail = "sms=delivered|http=failed|retrying")

        assertEquals(awkward, AlertRecords.decode(AlertRecords.encode(awkward)))
    }

    @Test
    fun anEmptyDetailSurvives() {
        assertEquals(record.copy(detail = ""), AlertRecords.decode("1761800000000|DURESS|"))
    }

    @Test
    fun rubbishDecodesToNothingRatherThanAGuess() {
        assertNull(AlertRecords.decode(""))
        assertNull(AlertRecords.decode("not-a-time|DURESS|x"))
        assertNull(AlertRecords.decode("1761800000000"))
        assertNull(AlertRecords.decode("1761800000000|NOT_A_REASON|x"))
    }

    @Test
    fun everyReasonIsNameable() {
        BreachReason.entries.forEach { reason ->
            assertEquals(reason, AlertRecords.reasonNamed(reason.name))
        }
        assertNull(AlertRecords.reasonNamed("WRONG_SECRET "))
    }

    @Test
    fun theStoreKeepsTheLastAlertAndNothingClearsIt() {
        val store = InMemorySettingsStore()
        assertNull(store.loadLastAlert())

        store.saveLastAlert(record)
        assertEquals(record, store.loadLastAlert())

        // Changing any other setting must not disturb it: standing down rewrites nothing here,
        // and that is the whole point of the record.
        store.saveSecret(null)
        assertEquals(record, store.loadLastAlert())
    }
}
