import XCTest
import SharedLogic
@testable import Halotap

/// Clearing an alert, driven across the Kotlin-to-Swift bridge the way the view drives it.
///
/// The shared tests already pin the rule. This pins the *call*: Kotlin default arguments do
/// not cross into Swift, so a signature that looks right in `commonMain` can arrive here
/// missing a parameter or defaulted to something else. That has already caused three bugs in
/// this app, and this particular call decides whether an attacker can clear the evidence.
final class StandDownBridgeTests: XCTestCase {

    private func breachedSession(
        penalties: StandDownPenaltyStore = InMemoryStandDownPenaltyStore(failures: 0, readyAtMillis: 0)
    ) -> StandbySession {
        let session = StandbySession(
            secret: DisarmSecret.companion.ofPin(digits: "1234"),
            maxAttempts: 2,
            graceMillis: 10_000,
            duressSecret: DisarmSecret.companion.ofPin(digits: "9876"),
            penalties: penalties
        )
        _ = session.hold(nowMillis: 0)
        _ = session.release(nowMillis: 0)
        _ = session.submit(entry: "0000", nowMillis: 1)
        _ = session.submit(entry: "0000", nowMillis: 2)
        return session
    }

    func testTheSetupActuallyBreaches() {
        XCTAssertTrue(breachedSession().state is StandbyStateBreached)
    }

    func testTheRealPinClearsTheAlert() {
        let session = breachedSession()
        XCTAssertTrue(session.clearBreach(entry: "1234", nowMillis: 100) is StandbyStateIdle)
    }

    func testAWrongCodeLeavesTheAlertStanding() {
        let session = breachedSession()
        XCTAssertTrue(session.clearBreach(entry: "0000", nowMillis: 100) is StandbyStateBreached)
    }

    func testTheDuressCodeDoesNotClearTheAlert() {
        // Whoever forced the duress code out of the user must not be able to tidy up with it.
        let session = breachedSession()
        XCTAssertTrue(session.clearBreach(entry: "9876", nowMillis: 100) is StandbyStateBreached)
    }

    func testClearingLeavesTheAppUsable() {
        let session = breachedSession()
        _ = session.clearBreach(entry: "1234", nowMillis: 100)
        XCTAssertTrue(session.hold(nowMillis: 105) is StandbyStateArmed)
    }

    func testAWrongCodeBuysAGrowingWait() {
        // Four digits is ten thousand guesses. The wait is what stops someone with the watch
        // and an evening from clearing the alert and the sign that it happened.
        let session = breachedSession()
        _ = session.clearBreach(entry: "0000", nowMillis: 100)
        XCTAssertEqual(session.standDownDelayMillis(nowMillis: 100), 2_000)

        // Refused during the wait even though it is the right code.
        XCTAssertTrue(session.clearBreach(entry: "1234", nowMillis: 500) is StandbyStateBreached)
        XCTAssertTrue(session.clearBreach(entry: "1234", nowMillis: 2_200) is StandbyStateIdle)
    }

    // MARK: - The penalty outliving the session

    func testTheWaitSurvivesTheSessionThatEarnedIt() {
        // The reason the penalty moved out of StandbySession's fields. A force-quit rebuilt
        // the session with the counter at zero, so the wait guarding the alert record could
        // be reset as fast as the app could be relaunched -- and on watchOS the crown
        // suspends rather than quits, so relaunching is the ordinary way to leave the app.
        let penalties = InMemoryStandDownPenaltyStore(failures: 0, readyAtMillis: 0)

        _ = breachedSession(penalties: penalties).clearBreach(entry: "0000", nowMillis: 100)

        let reopened = breachedSession(penalties: penalties)
        XCTAssertEqual(reopened.standDownDelayMillis(nowMillis: 100), 2_000)
        XCTAssertTrue(reopened.clearBreach(entry: "1234", nowMillis: 500) is StandbyStateBreached)
    }

    func testTheUserDefaultsStoreRoundTripsAcrossTheBridge() {
        // watchOS-specific, so no Kotlin test reaches it. Both values are stored as Doubles
        // rather than through setInteger, because NSInteger is 32-bit on arm64_32 and 64-bit
        // on the simulator -- so this is also the only place the choice is exercised.
        let suite = "halotap.tests.standdown"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }

        let store = UserDefaultsStandDownPenaltyStore(defaults: defaults)
        XCTAssertEqual(store.loadFailures(), 0)
        XCTAssertEqual(store.loadReadyAtMillis(), 0)

        store.save(failures: 3, readyAtMillis: 8_000)

        // A second instance over the same defaults, which is what a relaunch produces.
        let reopened = UserDefaultsStandDownPenaltyStore(defaults: defaults)
        XCTAssertEqual(reopened.loadFailures(), 3)
        XCTAssertEqual(reopened.loadReadyAtMillis(), 8_000)
    }

    func testADeadlineFromBeforeARebootIsNotHonoured() {
        // The stored deadline is a monotonic reading, which restarts near zero on reboot.
        // Read literally, last boot's value would lock the user out of their own alert screen.
        XCTAssertEqual(
            StandbySession.companion.standDownReadyAt(
                storedReadyAtMillis: 9_000_000,
                nowMillis: 1_200,
                maxPenaltyMillis: StandbySession.companion.MAX_STAND_DOWN_PENALTY_MILLIS
            ),
            0
        )
    }
}
