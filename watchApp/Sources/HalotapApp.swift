import SwiftUI
import SharedLogic

@main
struct HalotapApp: App {

    @State private var store: KeychainSettingsStore
    @State private var settings: HalotapSettings

    #if DEBUG
    // Debug only, and here rather than in StandbyView so the measurement cannot perturb the
    // screen it is measuring. A scene-phase change is the only moment the app is awake either
    // side of a suspension, so it is the only place a sleep can be observed at all.
    @Environment(\.scenePhase) private var scenePhase
    private let clockProbe = ClockProbeLog()
    #endif

    init() {
        let keychain = KeychainSettingsStore()
        _store = State(initialValue: keychain)
        _settings = State(initialValue: keychain.load())
    }

    var body: some Scene {
        WindowGroup {
            // No default PIN. An unconfigured install goes to setup rather than arming,
            // because a compiled-in fallback is exactly the thing that ends up shipping.
            if let secret = settings.secret {
                StandbyView(
                    secret: secret,
                    duressSecret: settings.duressSecret,
                    contacts: settings.contacts,
                    store: store,
                    onSettingsChanged: { settings = store.load() }
                )
                // Rebuilt when the contacts change, so a breach a moment later uses the
                // list the user just edited rather than the one captured at launch.
                .id(settings.contacts.count)
            } else {
                SetupView(store: store) { settings = store.load() }
            }
        }
        #if DEBUG
        .onChange(of: scenePhase) { _, phase in
            clockProbe.record(ClockProbe.sample(label: String(describing: phase)))
        }
        #endif
    }
}
