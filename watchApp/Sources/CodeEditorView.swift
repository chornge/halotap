import SwiftUI
import SharedLogic

/// Changing the PIN, or setting a duress code, after first run.
///
/// Drives the same `PinSetupFlow` as setup, so a PIN change is confirmed twice and a duress
/// code is still refused when it matches the PIN. Until this existed neither could ever be
/// changed without deleting the app — which for a code people share, forget, or have
/// watched over their shoulder is not a rough edge.
struct CodeEditorView: View {

    enum Mode: Identifiable {
        case changePin
        /// Carries the current secret so a new duress code can be checked against it.
        case setDuress(current: DisarmSecret)

        var id: String {
            switch self {
            case .changePin: return "pin"
            case .setDuress: return "duress"
            }
        }
    }

    let mode: Mode
    let onSaved: (PinSetupStateComplete) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var flow: PinSetupFlow
    @State private var state: any PinSetupState
    @State private var entry = ""

    init(mode: Mode, onSaved: @escaping (PinSetupStateComplete) -> Void) {
        self.mode = mode
        self.onSaved = onSaved

        let flow: PinSetupFlow
        switch mode {
        case .changePin:
            flow = PinSetupFlow(pinLength: Int32(pinLength), askForDuress: false, existingSecret: nil)
        case .setDuress(let current):
            flow = PinSetupFlow(pinLength: Int32(pinLength), askForDuress: true, existingSecret: current)
        }
        _flow = State(initialValue: flow)
        _state = State(initialValue: flow.state)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PinKeypad(
                entry: entry,
                caption: caption,
                captionTint: isProblem ? .red : .gray,
                onDigit: append,
                onBackspace: { entry = String(entry.dropLast()) }
            )
        }
    }

    private var isProblem: Bool {
        (state as? PinSetupStateChoosePin)?.afterMismatch == true ||
            (state as? PinSetupStateChooseDuress)?.sameAsPin == true
    }

    private var caption: String {
        if let choose = state as? PinSetupStateChoosePin {
            return choose.afterMismatch ? "Did not match. Start again" : "New PIN"
        }
        if let duress = state as? PinSetupStateChooseDuress {
            return duress.sameAsPin ? "Must differ from your PIN" : "New duress code"
        }
        if state is PinSetupStateConfirmPin { return "Enter it again" }
        return ""
    }

    private func append(_ digit: Character) {
        entry.append(digit)
        guard entry.count >= pinLength else { return }

        let completed = entry
        entry = ""
        state = flow.submit(entry: completed)

        if let done = state as? PinSetupStateComplete {
            onSaved(done)
            dismiss()
        }
    }
}
