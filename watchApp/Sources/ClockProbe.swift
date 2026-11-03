#if DEBUG
import Darwin
import Foundation

/// Measuring which of watchOS's clocks keep counting while the watch is asleep.
///
/// Not a feature, and compiled only into Debug builds. It exists to settle one question that
/// decides whether the central promise of this app holds on Apple Watch.
///
/// `AppClock.nowMillis()` used to read `mach_absolute_time`, which stops while a Darwin system
/// is suspended, and a watch suspends seconds after the wrist drops. It now reads
/// `CLOCK_MONOTONIC_RAW`, which counts sleep. This measures whether that holds on a real wrist,
/// which no simulator and no XCTest run can show: neither suspends the way a worn watch does.
///
/// It samples the app's own clock — not a copy of the expression it happens to use — alongside
/// every raw clock watchOS offers, so the verdict is about whatever `AppClock` is actually
/// wired to. Reverting that wiring would show up here as lost time.
///
/// Samples are kept where a relaunch cannot lose them, because the suspension is the event
/// being measured. See `docs/clock-measurement.md` for the procedure.
struct ClockSample: Codable, Equatable {

    /// `AppClock.nowMillis()` — the clock every deadline in the app is actually measured on,
    /// read through the same call the app makes. The column the verdict is about.
    let appClockMillis: Int64

    /// `DispatchTime.now().uptimeNanoseconds` in milliseconds — the clock this used to read,
    /// kept as a column so a reading shows what the old choice would have reported.
    let dispatchUptimeMillis: Int64

    /// `mach_continuous_time()` in milliseconds — the candidate replacement, documented to
    /// include time spent asleep and to reset on boot, which is what the armed-marker
    /// staleness check relies on.
    let continuousMillis: Int64

    /// `CLOCK_UPTIME_RAW` in milliseconds. Sampled alongside the two above so the measurement
    /// settles the POSIX semantics itself rather than resting on anyone's recollection of
    /// which of these pairs with which.
    let uptimeRawMillis: Int64

    /// `CLOCK_MONOTONIC_RAW` in milliseconds. Same reason.
    let monotonicRawMillis: Int64

    /// Milliseconds since the Unix epoch. The only reading certain to track real time, so it
    /// is what the others are judged against. It can be stepped by the network — which is why
    /// no deadline uses it — but not by enough to matter over a few minutes of measurement.
    let epochMillis: Int64

    /// Why this sample was taken, so a gap can be attributed to something the user did.
    let label: String
}

/// How much each clock thinks passed between two samples, against how much really did.
struct ClockSpan: Equatable {
    let from: String
    let to: String
    let wallMillis: Int64
    let appClockMillis: Int64
    let dispatchMillis: Int64
    let continuousMillis: Int64
    let uptimeRawMillis: Int64
    let monotonicRawMillis: Int64

    /// Real time the app's own clock failed to count.
    ///
    /// This is the whole measurement. A positive value is time the watch spent asleep that the
    /// app's deadlines did not notice passing — so a ten-second challenge really took ten
    /// seconds *plus this*, and a breach that should have fired was read as still live. After
    /// the move to `CLOCK_MONOTONIC_RAW` this is expected to be zero.
    var appClockLostMillis: Int64 { wallMillis - appClockMillis }

    /// What the previous choice of clock would have lost over the same gap. Non-zero here
    /// alongside a zero above is the fix working.
    var dispatchLostMillis: Int64 { wallMillis - dispatchMillis }

    var continuousLostMillis: Int64 { wallMillis - continuousMillis }
    var uptimeRawLostMillis: Int64 { wallMillis - uptimeRawMillis }
    var monotonicRawLostMillis: Int64 { wallMillis - monotonicRawMillis }
}

enum ClockProbe {

    /// Read every clock at as close to one instant as the platform allows.
    static func sample(label: String, now: Date = Date()) -> ClockSample {
        ClockSample(
            appClockMillis: AppClock.nowMillis(),
            dispatchUptimeMillis: Int64(DispatchTime.now().uptimeNanoseconds / nanosPerMilli),
            continuousMillis: machTicksToMillis(mach_continuous_time()),
            uptimeRawMillis: Int64(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / nanosPerMilli),
            monotonicRawMillis: Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) / nanosPerMilli),
            epochMillis: Int64(now.timeIntervalSince1970 * 1000),
            label: label
        )
    }

    /// Pure, so the arithmetic that the verdict rests on is tested rather than eyeballed on a
    /// watch screen.
    static func span(from earlier: ClockSample, to later: ClockSample) -> ClockSpan {
        ClockSpan(
            from: earlier.label,
            to: later.label,
            wallMillis: later.epochMillis - earlier.epochMillis,
            appClockMillis: later.appClockMillis - earlier.appClockMillis,
            dispatchMillis: later.dispatchUptimeMillis - earlier.dispatchUptimeMillis,
            continuousMillis: later.continuousMillis - earlier.continuousMillis,
            uptimeRawMillis: later.uptimeRawMillis - earlier.uptimeRawMillis,
            monotonicRawMillis: later.monotonicRawMillis - earlier.monotonicRawMillis
        )
    }

    /// Every consecutive pair, which is where a single sleep shows up.
    static func spans(_ samples: [ClockSample]) -> [ClockSpan] {
        guard samples.count > 1 else { return [] }
        return zip(samples, samples.dropFirst()).map { span(from: $0, to: $1) }
    }

    /// The span over which the app's clock lost the most, which is the one worth reading.
    /// Ordered on the old clock's loss when the app's is clean, so a healthy reading still
    /// surfaces the gap that proves the watch really did sleep.
    static func worstSpan(_ samples: [ClockSample]) -> ClockSpan? {
        spans(samples).max {
            ($0.appClockLostMillis, $0.dispatchLostMillis) < ($1.appClockLostMillis, $1.dispatchLostMillis)
        }
    }

    private static let nanosPerMilli: UInt64 = 1_000_000

    private static let timebase: (numer: UInt64, denom: UInt64) = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        // Both are 1 on every arm64 and x86_64 Apple platform, but reading them is free and
        // assuming them is the kind of thing that silently breaks on new hardware.
        return (UInt64(info.numer), UInt64(info.denom))
    }()

    private static func machTicksToMillis(_ ticks: UInt64) -> Int64 {
        // Divided before multiplying so a large numerator cannot overflow a UInt64. At
        // millisecond granularity the rounding this costs is irrelevant.
        Int64(ticks / timebase.denom * timebase.numer / nanosPerMilli)
    }
}

/// The samples, kept where a relaunch cannot lose them.
///
/// `UserDefaults` rather than the Keychain: there is nothing secret here, and the whole point
/// is to survive the app being suspended and relaunched, which is the event being measured.
struct ClockProbeLog {

    /// Enough for a long wear test without growing without bound. Oldest are dropped first.
    static let limit = 60

    private let defaults: UserDefaults
    private let key = "halotap.debug.clockSamples"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func samples() -> [ClockSample] {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([ClockSample].self, from: data)
        else { return [] }
        return decoded
    }

    func record(_ sample: ClockSample) {
        let updated = Self.appending(sample, to: samples(), limit: Self.limit)
        guard let data = try? JSONEncoder().encode(updated) else { return }
        defaults.set(data, forKey: key)
    }

    func clear() {
        defaults.removeObject(forKey: key)
    }

    /// Pure, so the bound is tested rather than trusted.
    static func appending(
        _ sample: ClockSample,
        to existing: [ClockSample],
        limit: Int = limit
    ) -> [ClockSample] {
        Array((existing + [sample]).suffix(limit))
    }
}

/// Human-readable durations, for a screen read by a person squinting at a watch.
enum ClockFormat {
    static func duration(_ millis: Int64) -> String {
        let total = abs(millis)
        let sign = millis < 0 ? "-" : ""
        if total < 1_000 { return "\(sign)\(total)ms" }
        let seconds = total / 1_000
        if seconds < 60 { return "\(sign)\(seconds).\((total % 1_000) / 100)s" }
        let minutes = seconds / 60
        if minutes < 60 { return "\(sign)\(minutes)m \(seconds % 60)s" }
        return "\(sign)\(minutes / 60)h \(minutes % 60)m"
    }

    /// The one line worth reading off the watch.
    ///
    /// A second of slack throughout: sampling five clocks is not atomic and the wall clock can
    /// be nudged by the network, so anything inside that is noise rather than a paused clock.
    static func verdict(_ span: ClockSpan) -> String {
        let lost = span.appClockLostMillis
        let slept = span.dispatchLostMillis

        if lost > 1_000 {
            return "FAIL: the app clock lost \(duration(lost)) of \(duration(span.wallMillis)). "
                + "A 10s deadline really took \(duration(10_000 + lost))."
        }
        // The app clock is clean. Whether that proves anything depends on the watch having
        // actually slept, which the old clock's loss is what shows.
        guard slept > 1_000 else {
            return "INCONCLUSIVE: the app clock counted the whole \(duration(span.wallMillis)), "
                + "but the watch never slept. Leave it longer with your wrist down."
        }
        return "PASS: the app clock counted the whole \(duration(span.wallMillis)) "
            + "across \(duration(slept)) of sleep. The old clock would have lost all of it."
    }
}
#endif
