package com.halotap.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinSetupFlowTest {

    @Test
    fun theHappyPathEndsWithTheChosenPinAndNoDuressCode() {
        val flow = PinSetupFlow()
        assertIs<PinSetupState.ChoosePin>(flow.state)

        assertEquals(PinSetupState.ConfirmPin, flow.submit("4821"))
        assertEquals(PinSetupState.OfferDuress, flow.submit("4821"))

        val done = assertIs<PinSetupState.Complete>(flow.skipDuress())
        assertTrue(done.secret.matches("4821"))
        assertNull(done.duress)
    }

    @Test
    fun aMismatchStartsOverRatherThanRetryingTheConfirmation() {
        // When the two differ, which was the typo is unknowable, so confirming again would
        // be guessing. A PIN mistyped during setup is one the user cannot enter under
        // pressure later, and the only way out would be deleting the app.
        val flow = PinSetupFlow()
        flow.submit("4821")

        val restarted = assertIs<PinSetupState.ChoosePin>(flow.submit("1111"))
        assertTrue(restarted.afterMismatch)

        // And the first entry is forgotten, so the restart is genuine.
        flow.submit("5555")
        assertEquals(PinSetupState.OfferDuress, flow.submit("5555"))
    }

    @Test
    fun theFirstScreenDoesNotClaimAMismatchBeforeOneHappens() {
        assertFalse(assertIs<PinSetupState.ChoosePin>(PinSetupFlow().state).afterMismatch)
    }

    @Test
    fun aDuressCodeIsCarriedThroughToCompletion() {
        val flow = PinSetupFlow()
        flow.submit("4821")
        flow.submit("4821")
        assertIs<PinSetupState.ChooseDuress>(flow.beginDuress())

        val done = assertIs<PinSetupState.Complete>(flow.submit("9876"))
        assertTrue(done.secret.matches("4821"))
        assertTrue(done.duress!!.matches("9876"))
    }

    @Test
    fun aDuressCodeEqualToThePinIsRefusedAndCanBeRetried() {
        val flow = PinSetupFlow()
        flow.submit("4821")
        flow.submit("4821")
        flow.beginDuress()

        val refused = assertIs<PinSetupState.ChooseDuress>(flow.submit("4821"))
        assertTrue(refused.sameAsPin)

        // Retrying works, and the refusal is cleared.
        val done = assertIs<PinSetupState.Complete>(flow.submit("9876"))
        assertTrue(done.duress!!.matches("9876"))
    }

    @Test
    fun entriesOfTheWrongLengthAreIgnored() {
        val flow = PinSetupFlow()
        assertIs<PinSetupState.ChoosePin>(flow.submit("12"))
        assertIs<PinSetupState.ChoosePin>(flow.submit("123456"))
        assertEquals(PinSetupState.ConfirmPin, flow.submit("4821"))
    }

    @Test
    fun skippingOrBeginningDuressOutOfOrderDoesNothing() {
        val flow = PinSetupFlow()
        assertIs<PinSetupState.ChoosePin>(flow.skipDuress())
        assertIs<PinSetupState.ChoosePin>(flow.beginDuress())
    }

    @Test
    fun completionIsTerminal() {
        val flow = PinSetupFlow()
        flow.submit("4821")
        flow.submit("4821")
        val done = flow.skipDuress()

        assertEquals(done, flow.submit("0000"))
        assertEquals(done, flow.beginDuress())
    }
}
