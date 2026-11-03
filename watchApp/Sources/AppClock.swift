import Darwin
import Foundation

/// The clocks the watch app reads, and the reasoning for which.
///
/// A named type rather than a private function in whichever view happened to need it first,
/// because which clock this is turned out to decide whether the alarm goes off at all. One
/// home for the choice means a second, subtly different `nowMillis()` cannot appear elsewhere,
/// and means the choice is testable.
enum AppClock {

    /// Monotonic milliseconds, **including time the watch spent asleep**.
    ///
    /// Every deadline in the app is measured on this. Two properties are required of it, and
    /// `CLOCK_MONOTONIC_RAW` is the only watchOS clock with both.
    ///
    /// **It must not be settable by the user.** The wall clock is out: someone holding the
    /// watch could push a challenge deadline out of reach by changing the time.
    ///
    /// **It must count time the watch spent asleep.** This is the part that was wrong. This
    /// read `DispatchTime.now().uptimeNanoseconds` — i.e. `mach_absolute_time()` — which on
    /// Darwin stops while the system is suspended. Measured on one ordinary host, up 1 day 1
    /// hour 38 minutes, that clock read 3,608 s against the continuous clock's 92,267 s: it
    /// had lost 24.6 hours, 96% of the elapsed real time.
    ///
    /// A watch suspends seconds after the wrist drops, which is exactly what happens when it
    /// is taken or dropped — the case this whole app is built for. So the ten-second challenge
    /// was ten seconds of *awake* time, and worse, `StandbyView.escalateMissedBreach` compares
    /// the stored deadline against a fresh reading of this clock: relaunched at the real
    /// deadline by its scheduled session, it was told the deadline had not arrived yet and
    /// escalated nothing. The wrist buzzed and the app reported there was nothing to report.
    ///
    /// Wear OS never had this: `SystemClock.elapsedRealtime()` counts sleep. The two platforms
    /// now agree, which is the point — `:sharedLogic` is handed one kind of number.
    ///
    /// Still resets on boot, which `StandbySession.isArmedMarkerUsable` and
    /// `StandbySession.standDownReadyAt` both rely on to recognise a reading from before a
    /// reboot: the measurement above shows it tracking time since boot, not since the epoch.
    ///
    /// `mach_continuous_time()` is the same clock; this spelling returns nanoseconds directly
    /// and needs no `mach_timebase_info` conversion. `ClockProbe` samples both and asserts
    /// they agree, so the equivalence is checked rather than assumed.
    static func nowMillis() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) / nanosPerMilli)
    }

    /// Milliseconds since the Unix epoch, for the one value that is reported rather than
    /// compared: `BreachContext.occurredAtMillis`, which leaves the device in the webhook body
    /// and in the user's own alert log.
    ///
    /// Nothing may be *decided* from this. It is the wall clock, so it can be changed by the
    /// user and stepped by the network, which is why no deadline uses it.
    static func epochMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static let nanosPerMilli: UInt64 = 1_000_000
}
