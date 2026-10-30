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
    let onDigit: (Character) -> Void
    let onBackspace: () -> Void

    var body: some View {
        VStack(spacing: 3) {
            Text(String(repeating: "●", count: entry.count)
                .padding(toLength: pinLength, withPad: "·", startingAt: 0))
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(.orange)

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
