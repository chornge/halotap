import XCTest
import SharedLogic
@testable import Halotap

/// The tokens that let a relaunch work out what it missed.
///
/// This is the whole of watchOS durability: there is no AlarmManager, so if the app is
/// reopened after a deadline it has to reconstruct what happened from what it wrote down.
final class BreachTokensTests: XCTestCase {

    private var tokens: UserDefaultsBreachTokens!

    override func setUp() {
        super.setUp()
        tokens = UserDefaultsBreachTokens(defaults: .standard)
        tokens.clearOpen()
        tokens.markHandled(token: 0)
    }

    override func tearDown() {
        tokens.clearOpen()
        tokens.markHandled(token: 0)
        super.tearDown()
    }

    func testNoOpenBreachByDefault() {
        XCTAssertEqual(tokens.openToken(), 0)
    }

    func testAnOpenTokenRoundTrips() {
        tokens.open(token: 1_234_567)
        XCTAssertEqual(tokens.openToken(), 1_234_567)
    }

    func testLargeTokensSurvive() {
        // Stored as a Double because NSInteger is 32-bit on arm64_32, which every Apple
        // Watch since Series 4 is. A millisecond clock passes 2^31 in about 24 days.
        let beyond32Bit: Int64 = 9_000_000_000
        tokens.open(token: beyond32Bit)
        XCTAssertEqual(tokens.openToken(), beyond32Bit)
    }

    func testClearingRemovesTheOpenToken() {
        tokens.open(token: 99)
        tokens.clearOpen()
        XCTAssertEqual(tokens.openToken(), 0)
    }

    func testHandledIsTrackedSeparately() {
        tokens.open(token: 500)
        tokens.markHandled(token: 500)
        XCTAssertEqual(tokens.openToken(), 500)
        XCTAssertEqual(tokens.handledToken(), 500)
    }

    func testTokensSurviveANewInstance() {
        tokens.open(token: 4_242)
        XCTAssertEqual(UserDefaultsBreachTokens(defaults: .standard).openToken(), 4_242)
    }

    func testARecentlyMissedBreachIsDecidedAsEscalate() {
        // The tokens feed MissedBreach, which is where the rule lives.
        tokens.open(token: 10_000)
        let action = MissedBreach.shared.actionFor(
            openDeadlineMillis: tokens.openToken(),
            handledDeadlineMillis: tokens.handledToken(),
            nowMillis: 11_000,
            maxLateMillis: MissedBreach.shared.MAX_LATE_ESCALATION_MILLIS
        )
        XCTAssertEqual(action, .escalate)
    }

    func testAnAlreadyHandledBreachIsNotSentAgain() {
        tokens.open(token: 10_000)
        tokens.markHandled(token: 10_000)
        let action = MissedBreach.shared.actionFor(
            openDeadlineMillis: tokens.openToken(),
            handledDeadlineMillis: tokens.handledToken(),
            nowMillis: 11_000,
            maxLateMillis: MissedBreach.shared.MAX_LATE_ESCALATION_MILLIS
        )
        XCTAssertEqual(action, .nothing)
    }
}
