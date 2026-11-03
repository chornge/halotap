import XCTest
@testable import Halotap

/// The arithmetic the clock measurement's verdict rests on.
///
/// The measurement itself needs a real watch asleep on a real wrist — neither a simulator nor
/// XCTest suspends the way that does. What *can* be pinned here is that the numbers the watch
/// screen reports are computed correctly, so that when the reading comes back it can be
/// trusted rather than re-derived by hand.
final class ClockProbeTests: XCTestCase {

    private func sample(
        label: String,
        appClock: Int64,
        dispatch: Int64,
        continuous: Int64,
        uptimeRaw: Int64,
        monotonicRaw: Int64,
        epoch: Int64
    ) -> ClockSample {
        ClockSample(
            appClockMillis: appClock,
            dispatchUptimeMillis: dispatch,
            continuousMillis: continuous,
            uptimeRawMillis: uptimeRaw,
            monotonicRawMillis: monotonicRaw,
            epochMillis: epoch,
            label: label
        )
    }

    /// A watch that slept for five minutes, wired as the app is now: the app clock and the
    /// sleep-counting clocks advance by the full five minutes, the uptime clocks only by the
    /// two seconds the app was actually awake.
    private func sleptFiveMinutes() -> [ClockSample] {
        [
            sample(label: "inactive", appClock: 10_000, dispatch: 10_000, continuous: 10_000,
                   uptimeRaw: 10_000, monotonicRaw: 10_000, epoch: 1_700_000_000_000),
            sample(label: "active", appClock: 312_000, dispatch: 12_000, continuous: 312_000,
                   uptimeRaw: 12_000, monotonicRaw: 312_000, epoch: 1_700_000_302_000),
        ]
    }

    /// The same watch, wired as the app used to be: the app clock is the uptime clock, so it
    /// stops with the watch. This is the reading the fix exists to turn into the one above.
    private func sleptFiveMinutesOnTheOldClock() -> [ClockSample] {
        sleptFiveMinutes().map { original in
            sample(label: original.label, appClock: original.dispatchUptimeMillis,
                   dispatch: original.dispatchUptimeMillis, continuous: original.continuousMillis,
                   uptimeRaw: original.uptimeRawMillis, monotonicRaw: original.monotonicRawMillis,
                   epoch: original.epochMillis)
        }
    }

    // MARK: - Spans

    func testTheWallClockSpanIsRealElapsedTime() {
        let span = ClockProbe.span(from: sleptFiveMinutes()[0], to: sleptFiveMinutes()[1])
        XCTAssertEqual(span.wallMillis, 302_000)
    }

    func testTheFixedAppClockLosesNothingAcrossASleep() {
        let span = ClockProbe.span(from: sleptFiveMinutes()[0], to: sleptFiveMinutes()[1])
        XCTAssertEqual(span.appClockMillis, 302_000)
        XCTAssertEqual(span.appClockLostMillis, 0)
        // The old clock still reported only the two seconds the app was awake, which is what
        // makes the reading proof rather than coincidence.
        XCTAssertEqual(span.dispatchMillis, 2_000)
        XCTAssertEqual(span.dispatchLostMillis, 300_000)
    }

    func testTheOldClockLosesTheWholeSleep() {
        // The number the measurement originally existed to produce: 300 seconds of real time
        // the app's deadlines never saw pass, which is how much a challenge would have
        // stretched by, and why escalateMissedBreach read a passed deadline as still live.
        let old = sleptFiveMinutesOnTheOldClock()
        let span = ClockProbe.span(from: old[0], to: old[1])
        XCTAssertEqual(span.appClockLostMillis, 300_000)
    }

    func testAClockThatCountsSleepLosesNothing() {
        let span = ClockProbe.span(from: sleptFiveMinutes()[0], to: sleptFiveMinutes()[1])
        XCTAssertEqual(span.continuousLostMillis, 0)
        XCTAssertEqual(span.monotonicRawLostMillis, 0)
        XCTAssertEqual(span.uptimeRawLostMillis, 300_000)
    }

    func testOneSampleHasNoSpanToReport() {
        XCTAssertTrue(ClockProbe.spans([sleptFiveMinutes()[0]]).isEmpty)
        XCTAssertTrue(ClockProbe.spans([]).isEmpty)
        XCTAssertNil(ClockProbe.worstSpan([]))
    }

    func testTheWorstGapIsTheOneSurfaced() {
        // A wear test produces many gaps and only the largest matters; a screen that showed
        // the latest would report whatever happened to be last rather than the finding.
        var samples = sleptFiveMinutesOnTheOldClock()
        samples.append(
            sample(label: "inactive", appClock: 13_000, dispatch: 13_000, continuous: 313_000,
                   uptimeRaw: 13_000, monotonicRaw: 313_000, epoch: 1_700_000_303_000)
        )
        let worst = ClockProbe.worstSpan(samples)
        XCTAssertEqual(worst?.appClockLostMillis, 300_000)
        XCTAssertEqual(worst?.to, "active")
    }

    // MARK: - The verdict line

    func testAnAppClockThatStoppedReadsAsAFailureWithTheConsequenceSpelledOut() {
        let old = sleptFiveMinutesOnTheOldClock()
        let verdict = ClockFormat.verdict(ClockProbe.span(from: old[0], to: old[1]))
        XCTAssertTrue(verdict.hasPrefix("FAIL:"), verdict)
        XCTAssertTrue(verdict.contains("lost 5m 0s"), verdict)
        // The consequence spelled out, so nobody has to do the sum on a watch face.
        XCTAssertTrue(verdict.contains("10s deadline really took 5m 10s"), verdict)
    }

    func testHoldingAcrossRealSleepReadsAsAPass() {
        let verdict = ClockFormat.verdict(
            ClockProbe.span(from: sleptFiveMinutes()[0], to: sleptFiveMinutes()[1])
        )
        XCTAssertTrue(verdict.hasPrefix("PASS:"), verdict)
        XCTAssertTrue(verdict.contains("across 5m 0s of sleep"), verdict)
    }

    func testAWatchThatNeverSleptProvesNothingEitherWay() {
        // The trap this guards: a clean app clock over a gap the watch stayed awake for looks
        // identical to a clean app clock over a gap it slept through, and only the second is
        // evidence. Reporting the first as a pass would retire the question wrongly.
        let awake = [
            sample(label: "a", appClock: 1_000, dispatch: 1_000, continuous: 1_000,
                   uptimeRaw: 1_000, monotonicRaw: 1_000, epoch: 1_700_000_000_000),
            sample(label: "b", appClock: 61_000, dispatch: 61_000, continuous: 61_000,
                   uptimeRaw: 61_000, monotonicRaw: 61_000, epoch: 1_700_000_060_000),
        ]
        let verdict = ClockFormat.verdict(ClockProbe.span(from: awake[0], to: awake[1]))
        XCTAssertTrue(verdict.hasPrefix("INCONCLUSIVE:"), verdict)
    }

    func testSubSecondNoiseIsNotReportedAsAPausedClock() {
        // Sampling six clocks is not atomic and the wall clock can be nudged by the network.
        // Without the slack, every ordinary gap would read as a finding.
        let jittery = [
            sample(label: "a", appClock: 1_000, dispatch: 1_000, continuous: 1_000,
                   uptimeRaw: 1_000, monotonicRaw: 1_000, epoch: 1_700_000_000_000),
            sample(label: "b", appClock: 61_000, dispatch: 61_000, continuous: 61_000,
                   uptimeRaw: 61_000, monotonicRaw: 61_000, epoch: 1_700_000_060_400),
        ]
        let verdict = ClockFormat.verdict(ClockProbe.span(from: jittery[0], to: jittery[1]))
        XCTAssertFalse(verdict.hasPrefix("FAIL:"), verdict)
    }

    // MARK: - The log

    func testTheLogIsBoundedAndDropsTheOldestFirst() {
        // The conversion is hoisted deliberately: six inferred Int64($0) in one call plus a
        // string interpolation is enough to time the Swift type-checker out.
        let many: [ClockSample] = (0..<10).map { index in
            let value = Int64(index)
            return sample(label: "s\(index)", appClock: value, dispatch: value,
                          continuous: value, uptimeRaw: value, monotonicRaw: value, epoch: value)
        }
        var kept: [ClockSample] = []
        for one in many { kept = ClockProbeLog.appending(one, to: kept, limit: 4) }

        XCTAssertEqual(kept.count, 4)
        XCTAssertEqual(kept.map(\.label), ["s6", "s7", "s8", "s9"])
    }

    func testSamplesSurviveBeingWrittenAndReadBack() {
        // The samples have to outlive a suspension, which is the event being measured, so the
        // round trip through UserDefaults is part of the instrument rather than incidental.
        let suite = "halotap.tests.clockprobe"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }

        let log = ClockProbeLog(defaults: defaults)
        XCTAssertTrue(log.samples().isEmpty)

        log.record(sleptFiveMinutes()[0])
        log.record(sleptFiveMinutes()[1])

        let reopened = ClockProbeLog(defaults: defaults)
        XCTAssertEqual(reopened.samples(), sleptFiveMinutes())
        XCTAssertEqual(ClockProbe.worstSpan(reopened.samples())?.dispatchLostMillis, 300_000)
        XCTAssertEqual(ClockProbe.worstSpan(reopened.samples())?.appClockLostMillis, 0)

        reopened.clear()
        XCTAssertTrue(reopened.samples().isEmpty)
    }

    // MARK: - The live clocks

    func testEveryClockIsReadAndAdvances() {
        // Not a measurement of sleep -- XCTest keeps the process awake -- only that all five
        // readings are real and move forward, so a zero on the watch means a paused clock and
        // not a column that was never wired up.
        let first = ClockProbe.sample(label: "first")
        Thread.sleep(forTimeInterval: 0.05)
        let second = ClockProbe.sample(label: "second")

        XCTAssertGreaterThan(second.appClockMillis, first.appClockMillis)
        XCTAssertGreaterThan(second.dispatchUptimeMillis, first.dispatchUptimeMillis)
        XCTAssertGreaterThan(second.continuousMillis, first.continuousMillis)
        XCTAssertGreaterThan(second.uptimeRawMillis, first.uptimeRawMillis)
        XCTAssertGreaterThan(second.monotonicRawMillis, first.monotonicRawMillis)
        XCTAssertGreaterThan(second.epochMillis, first.epochMillis)
    }

    func testTheTwoApisForTheSameClockAgree() {
        // DispatchTime pairs with CLOCK_UPTIME_RAW, mach_continuous_time with
        // CLOCK_MONOTONIC_RAW. If either pair disagreed, the measurement could not attribute
        // a gap to a clock, and AppClock's comment about the equivalence would be wrong.
        let now = ClockProbe.sample(label: "now")
        XCTAssertLessThan(abs(now.dispatchUptimeMillis - now.uptimeRawMillis), 50)
        XCTAssertLessThan(abs(now.continuousMillis - now.monotonicRawMillis), 50)
    }

    // MARK: - What AppClock is wired to

    func testTheAppClockIsTheOneThatCountsSleep() {
        // The assertion that fails if anyone puts nowMillis() back on DispatchTime. Stated as
        // agreement with the continuous clock rather than as a difference from the uptime one,
        // because on a host that has never slept those two are identical and such a test would
        // pass or fail on the runner's uptime rather than on the code.
        let now = ClockProbe.sample(label: "now")
        XCTAssertLessThan(
            abs(now.appClockMillis - now.continuousMillis), 50,
            "AppClock.nowMillis() must read a clock that counts time spent asleep"
        )
    }

    func testTheAppClockIsNeverBehindTheUptimeClock() {
        // Continuous time is uptime plus whatever the device slept, so the app's clock can
        // only ever be at or ahead of it. The difference is this host's sleep since boot.
        let now = ClockProbe.sample(label: "now")
        XCTAssertGreaterThanOrEqual(now.appClockMillis, now.uptimeRawMillis - 50)
    }

    func testTheAppClockIsNotTheWallClock() {
        // The other half of the requirement: a user-settable clock would let someone holding
        // the watch push a deadline out of reach. Time since boot is many orders of magnitude
        // short of time since 1970, so this cannot be a close call.
        let now = ClockProbe.sample(label: "now")
        XCTAssertLessThan(now.appClockMillis, now.epochMillis / 2)
    }

}
