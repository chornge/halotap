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
                    contacts: settings.contacts
                )
            } else {
                SetupView(store: store) { settings = store.load() }
            }
        }
    }
}
