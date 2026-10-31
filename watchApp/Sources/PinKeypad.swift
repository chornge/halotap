import SwiftUI

/// Four digits are the PIN length everywhere, shared so setup and disarm cannot disagree.
let pinLength = 4

/// The digit pad, shared by disarming and by first-run setup.
///
/// `caption` carries whatever the caller needs to say above the keys — attempts remaining
/// when disarming, the step when setting up — because the keys themselves are identical
/// either way and a second near-copy of this would drift. The Wear app has the same split
/// for the same reason.
struct PinKeypad: View {
    let entry: String
    let caption: String
    let captionTint: Color
    /// How much of the disarm window is left, 1.0 down to 0.0. Nil everywhere there is no
    /// deadline, which is every use of this pad except the challenge itself.
    var remainingFraction: (() -> Double)?
    let onDigit: (Character) -> Void
    let onBackspace: () -> Void

    var body: some View {
        VStack(spacing: 3) {
            Text(String(repeating: "●", count: entry.count)
                .padding(toLength: pinLength, withPad: "·", startingAt: 0))
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(.orange)

            if let remainingFraction {
                // Redrawn from the clock rather than animated towards a target, so the bar
                // cannot drift away from the deadline the session will actually breach on.
                // Capped well below the display's rate: a 10-second drain reads as smooth at
                // 20fps, and this runs while the watch is awake and the user is typing.
                TimelineView(.animation(minimumInterval: 1.0 / 20.0)) { _ in
                    CountdownBar(fraction: remainingFraction())
                }
            }

            Text(caption)
                .font(.system(size: 10))
                .foregroundColor(captionTint)
                .multilineTextAlignment(.center)

            ForEach(["123", "456", "789"], id: \.self) { row in
                HStack(spacing: 4) {
                    ForEach(Array(row), id: \.self) { digit in
                        key(String(digit)) { onDigit(digit) }
                    }
                }
            }
            HStack(spacing: 4) {
                key("0") { onDigit("0") }
                key("⌫", action: onBackspace)
            }
        }
    }

    private func key(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 15))
                .foregroundColor(.white)
                .frame(width: 32, height: 28)
        }
        .buttonStyle(.plain)
        .background(Color(white: 0.12))
        .clipShape(Capsule())
    }
}

/// How much of the disarm window is left, as a bar that drains left to right.
///
/// Deliberately not a number. A ticking "7, 6, 5" reads as a threat and is harder to take in
/// at a glance in the dark than a length, and the exact second is not what the user needs —
/// whether there is time to finish typing is. It turns red near the end so the state is
/// legible from colour alone. The Wear app draws the same bar for the same reasons.
private struct CountdownBar: View {
    let fraction: Double

    /// Below this much of the window left, the countdown turns red.
    private static let lowTime = 0.3

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(Color(white: 0.12))
                Capsule()
                    .fill(fraction > Self.lowTime ? Color.orange : Color.red)
                    .frame(width: geometry.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(width: 88, height: 4)
    }
}
