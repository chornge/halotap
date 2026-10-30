import SwiftUI
import SharedLogic

/// First run on the watch: choose the PIN, then optionally a duress code.
///
/// Entirely on-device. Unlike Wear OS, watchOS needs no companion for this — and until it
/// existed the Apple Watch build shipped a compiled-in `1234`, which is exactly the kind of
/// placeholder that ends up in a release.
///
/// The PIN is asked for twice because one mistyped during setup is one the user cannot enter
/// under pressure later, and the only way out would be deleting the app.
struct SetupView: View {
    let store: SettingsStore
    let onFinished: () -> Void

    // The rules live in PinSetupFlow, shared with Wear OS. They are small but easy to get
    // subtly wrong, and getting them wrong on one platform only is exactly what happened
    // with the attempt count.
    @State private var flow = PinSetupFlow(pinLength: Int32(pinLength))
    @State private var state: any PinSetupState = PinSetupStateChoosePin(afterMismatch: false)
    @State private var entry = ""

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if state is PinSetupStateOfferDuress {
                duressOffer
            } else {
                PinKeypad(
                    entry: entry,
                    caption: caption,
                    captionTint: isProblem ? .red : .gray,
                    onDigit: append,
                    onBackspace: { entry = String(entry.dropLast()) }
                )
            }
        }
    }

    private var isProblem: Bool {
        (state as? PinSetupStateChoosePin)?.afterMismatch == true ||
            (state as? PinSetupStateChooseDuress)?.sameAsPin == true
    }

    private var caption: String {
        if let choose = state as? PinSetupStateChoosePin {
            return choose.afterMismatch ? "Did not match. Start again" : "Choose a PIN"
        }
        if let duress = state as? PinSetupStateChooseDuress {
            return duress.sameAsPin ? "Must differ from your PIN" : "Choose a duress code"
        }
        if state is PinSetupStateConfirmPin { return "Enter it again" }
        return ""
    }

    private var duressOffer: some View {
        VStack(spacing: 8) {
            Text("Duress code?")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(.orange)
            Text("A second code that looks like disarming but silently alerts instead. For when someone is standing over you.")
                .font(.system(size: 11))
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
            HStack(spacing: 6) {
                Button("Skip") { advance(flow.skipDuress()) }
                Button("Set") { advance(flow.beginDuress()) }
            }
            .font(.system(size: 13))
        }
        .padding(.horizontal, 8)
    }

    private func append(_ digit: Character) {
        entry.append(digit)
        guard entry.count >= pinLength else { return }

        let completed = entry
        entry = ""
        advance(flow.submit(entry: completed))
    }

    /// Saving happens here rather than in the flow, which performs no I/O by design.
    private func advance(_ next: any PinSetupState) {
        state = next
        guard let done = next as? PinSetupStateComplete else { return }

        store.saveSecret(secret: done.secret)
        store.saveDuressSecret(secret: done.duress)

        // Asked for here rather than at a deadline. A permission prompt appearing during a
        // breach would be both useless and dangerous, and without it the only mechanism that
        // survives the app being closed is gone.
        BreachScheduler.requestNotificationAuthorization()
        onFinished()
    }
}
