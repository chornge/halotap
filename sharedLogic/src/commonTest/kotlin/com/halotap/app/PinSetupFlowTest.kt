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

class PinChangeFlowTest {

    @Test
    fun changingOnlyThePinSkipsTheDuressOffer() {
        // Settings edits the duress code separately. Dragging the user back through it on
        // every PIN change would be a trap.
        val flow = PinSetupFlow(askForDuress = false)
        assertEquals(PinSetupState.ConfirmPin, flow.submit("5555"))

        val done = assertIs<PinSetupState.Complete>(flow.submit("5555"))
        assertTrue(done.secret.matches("5555"))
        assertNull(done.duress)
    }

    @Test
    fun aPinChangeStillRequiresConfirmation() {
        val flow = PinSetupFlow(askForDuress = false)
        flow.submit("5555")

        val restarted = assertIs<PinSetupState.ChoosePin>(flow.submit("6666"))
        assertTrue(restarted.afterMismatch)
    }

    @Test
    fun changingOnlyTheDuressCodeStartsAtTheDuressStep() {
        val existing = DisarmSecret.ofPin("4821")
        val flow = PinSetupFlow(existingSecret = existing)
        assertIs<PinSetupState.ChooseDuress>(flow.state)

        val done = assertIs<PinSetupState.Complete>(flow.submit("9876"))
        // The existing secret is carried through untouched, so saving cannot clobber it.
        assertTrue(done.secret.matches("4821"))
        assertTrue(done.duress!!.matches("9876"))
    }

    @Test
    fun aDuressChangeIsCheckedAgainstTheRealPin() {
        // Settings has the secret, not the raw digits, so the comparison must go through
        // matches() rather than a remembered string.
        val flow = PinSetupFlow(existingSecret = DisarmSecret.ofPin("4821"))

        val refused = assertIs<PinSetupState.ChooseDuress>(flow.submit("4821"))
        assertTrue(refused.sameAsPin)

        assertIs<PinSetupState.Complete>(flow.submit("9876"))
    }
}
