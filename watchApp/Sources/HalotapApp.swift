import SwiftUI
import SharedLogic

@main
struct HalotapApp: App {

    @State private var store: KeychainSettingsStore
    @State private var settings: HalotapSettings

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
    }
}
