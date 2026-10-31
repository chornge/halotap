import XCTest
import SharedLogic
@testable import Halotap

/// The disarm countdown, driven across the Kotlin-to-Swift bridge the way the keypad drives it.
///
/// The shared tests pin the arithmetic. This pins the *call*, because the bar is what tells
/// the user whether there is still time to finish typing: a fraction that arrives wrong here
/// would either rush someone who had seconds left or reassure someone who had none.
final class CountdownBridgeTests: XCTestCase {

    private func challenging() -> StandbySession {
        let session = StandbySession(
            secret: DisarmSecret.companion.ofPin(digits: "1234"),
            maxAttempts: 2,
            graceMillis: 10_000,
            duressSecret: nil,
            penalties: InMemoryStandDownPenaltyStore(failures: 0, readyAtMillis: 0)
        )
        _ = session.hold(nowMillis: 0)
        _ = session.release(nowMillis: 0)
        return session
    }

    func testFullWhenTheChallengeOpens() {
        XCTAssertEqual(challenging().remainingFraction(nowMillis: 0), 1.0, accuracy: 0.0001)
    }

    func testItDrainsWithTheClock() {
        let session = challenging()
        XCTAssertEqual(session.remainingFraction(nowMillis: 5_000), 0.5, accuracy: 0.0001)
        XCTAssertEqual(session.remainingMillis(nowMillis: 5_000), 7_500 - 2_500)
    }

    func testItStopsAtEmpty() {
        let session = challenging()
        XCTAssertEqual(session.remainingFraction(nowMillis: 30_000), 0.0, accuracy: 0.0001)
        XCTAssertEqual(session.remainingMillis(nowMillis: 30_000), 0)
    }

    func testAWrongEntryRefillsIt() {
        let session = challenging()
        _ = session.submit(entry: "0000", nowMillis: 9_000)
        XCTAssertEqual(session.remainingFraction(nowMillis: 9_000), 1.0, accuracy: 0.0001)
    }

    func testNothingToShowOutsideAChallenge() {
        let session = StandbySession(
            secret: DisarmSecret.companion.ofPin(digits: "1234"),
            maxAttempts: 2,
            graceMillis: 10_000,
            duressSecret: nil,
            penalties: InMemoryStandDownPenaltyStore(failures: 0, readyAtMillis: 0)
        )
        XCTAssertEqual(session.remainingFraction(nowMillis: 0), 0.0, accuracy: 0.0001)
    }
}
