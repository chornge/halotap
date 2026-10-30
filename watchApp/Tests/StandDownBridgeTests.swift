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

    private func breachedSession() -> StandbySession {
        let session = StandbySession(
            secret: DisarmSecret.companion.ofPin(digits: "1234"),
            maxAttempts: 2,
            graceMillis: 10_000,
            duressSecret: DisarmSecret.companion.ofPin(digits: "9876")
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
        XCTAssertTrue(session.clearBreach(entry: "1234") is StandbyStateIdle)
    }

    func testAWrongCodeLeavesTheAlertStanding() {
        let session = breachedSession()
        XCTAssertTrue(session.clearBreach(entry: "0000") is StandbyStateBreached)
    }

    func testTheDuressCodeDoesNotClearTheAlert() {
        // Whoever forced the duress code out of the user must not be able to tidy up with it.
        let session = breachedSession()
        XCTAssertTrue(session.clearBreach(entry: "9876") is StandbyStateBreached)
    }

    func testClearingLeavesTheAppUsable() {
        let session = breachedSession()
        _ = session.clearBreach(entry: "1234")
        XCTAssertTrue(session.hold(nowMillis: 5) is StandbyStateArmed)
    }
}
