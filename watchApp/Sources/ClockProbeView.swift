#if DEBUG
import SwiftUI

/// The readout for [ClockProbe], reachable from Settings in Debug builds only.
///
/// Shown on the watch rather than logged, because reading a watch's log needs it paired to a
/// Mac with Console open — and the measurement requires the watch to be worn, asleep, and away
/// from the desk. The verdict has to be legible on the wrist afterwards.
struct ClockProbeView: View {

    private let log = ClockProbeLog()
    @State private var samples: [ClockSample] = []

    var body: some View {
        List {
            Section {
                if let worst = ClockProbe.worstSpan(samples) {
                    Text(ClockFormat.verdict(worst))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(verdictTint(worst))
                } else {
                    Text("Wear the watch, let it sleep a few minutes, then raise your wrist.")
                        .font(.system(size: 12))
                        .foregroundColor(.gray)
                }
            } header: {
                Text("Verdict")
            } footer: {
                Text("The worst gap between two samples. \(samples.count) sample(s).")
                    .font(.system(size: 10))
            }

            if let worst = ClockProbe.worstSpan(samples) {
                Section("That gap, per clock") {
                    row("wall clock", worst.wallMillis, lost: 0)
                    row("app clock", worst.appClockMillis, lost: worst.appClockLostMillis)
                    row("old (DispatchTime)", worst.dispatchMillis, lost: worst.dispatchLostMillis)
                    row("continuous", worst.continuousMillis, lost: worst.continuousLostMillis)
                    row("UPTIME_RAW", worst.uptimeRawMillis, lost: worst.uptimeRawLostMillis)
                    row("MONOTONIC_RAW", worst.monotonicRawMillis, lost: worst.monotonicRawLostMillis)
                }
            }

            Section("Every gap") {
                // Newest first: the interesting one is whatever just happened.
                ForEach(Array(ClockProbe.spans(samples).enumerated().reversed()), id: \.offset) { pair in
                    VStack(alignment: .leading, spacing: 1) {
                        Text("\(pair.element.from) → \(pair.element.to)")
                            .font(.system(size: 11))
                        Text(
                            "real \(ClockFormat.duration(pair.element.wallMillis)), "
                                + "app lost \(ClockFormat.duration(pair.element.appClockLostMillis)), "
                                + "slept \(ClockFormat.duration(pair.element.dispatchLostMillis))"
                        )
                        .font(.system(size: 10))
                        .foregroundColor(pair.element.appClockLostMillis > 1_000 ? .red : .gray)
                    }
                }
            }

            Section {
                Button("Take a sample now") { record("manual") }
                Button("Clear samples", role: .destructive) {
                    log.clear()
                    samples = []
                }
            }
        }
        .navigationTitle("Clock probe")
        .onAppear {
            // Recorded before reading, so opening the screen is itself the closing sample of
            // whatever gap just happened.
            record("opened probe")
        }
    }

    /// Red when the app's clock lost time, green when it held across real sleep, orange when
    /// the watch never slept and the reading therefore proves nothing either way.
    private func verdictTint(_ span: ClockSpan) -> Color {
        if span.appClockLostMillis > 1_000 { return .red }
        return span.dispatchLostMillis > 1_000 ? .green : .orange
    }

    private func record(_ label: String) {
        log.record(ClockProbe.sample(label: label))
        samples = log.samples()
    }

    private func row(_ name: String, _ millis: Int64, lost: Int64) -> some View {
        HStack {
            Text(name).font(.system(size: 11))
            Spacer()
            Text(ClockFormat.duration(millis))
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(lost > 1_000 ? .red : .primary)
        }
    }
}
#endif
