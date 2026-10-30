import SwiftUI
import SharedLogic

/// Who gets alerted, and the state of the codes.
///
/// Reachable only while idle. When the watch is armed the whole screen is a hold target and
/// a stray tap must never open a menu — and nobody should be editing contacts mid-walk.
struct SettingsView: View {
    let store: SettingsStore
    let onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var settings: HalotapSettings
    @State private var pickingContact = false
    @State private var editing: CodeEditorView.Mode?

    init(store: SettingsStore, onChanged: @escaping () -> Void) {
        self.store = store
        self.onChanged = onChanged
        _settings = State(initialValue: store.load())
    }

    var body: some View {
        List {
            Section {
                if settings.contacts.isEmpty {
                    // Stated as a consequence, not a status. "None" alone would not tell
                    // anyone that a breach currently reaches nobody at all.
                    Text("No one will be alerted yet.")
                        .font(.system(size: 12))
                        .foregroundColor(.red)
                } else {
                    ForEach(settings.contacts, id: \.phoneNumber) { contact in
                        VStack(alignment: .leading, spacing: 1) {
                            Text(contact.name).font(.system(size: 14))
                            Text(contact.phoneNumber)
                                .font(.system(size: 11))
                                .foregroundColor(.gray)
                        }
                        .swipeActions {
                            Button("Remove", role: .destructive) { remove(contact) }
                        }
                    }
                }
                Button("Add contact") { pickingContact = true }
                    .font(.system(size: 13))
            } header: {
                Text("Emergency contacts")
            }

            Section {
                Button("Change PIN") { editing = .changePin }
                    .font(.system(size: 13))
            } header: {
                Text("Disarm PIN")
            }

            Section {
                Text(settings.duressSecret == nil ? "Not set" : "Set")
                    .font(.system(size: 12))
                    .foregroundColor(.gray)
                if let secret = settings.secret {
                    Button(settings.duressSecret == nil ? "Set duress code" : "Change duress code") {
                        editing = .setDuress(current: secret)
                    }
                    .font(.system(size: 13))
                }
                if settings.duressSecret != nil {
                    Button("Remove duress code", role: .destructive) {
                        store.saveDuressSecret(secret: nil)
                        reload()
                    }
                    .font(.system(size: 13))
                }
            } header: {
                Text("Duress code")
            }
        }
        .sheet(item: $editing) { mode in
            CodeEditorView(mode: mode) { done in
                switch mode {
                case .changePin:
                    // Only the PIN. A duress code already set must survive a PIN change.
                    store.saveSecret(secret: done.secret)
                case .setDuress:
                    store.saveDuressSecret(secret: done.duress)
                }
                reload()
            }
        }
        .sheet(isPresented: $pickingContact) {
            ContactPickerView { contact in
                add(contact)
                pickingContact = false
            }
        }
    }

    private func add(_ contact: EmergencyContact) {
        // The same number twice would text the person twice on a breach.
        guard !settings.contacts.contains(where: { $0.phoneNumber == contact.phoneNumber }) else {
            return
        }
        store.saveContacts(contacts: settings.contacts + [contact])
        reload()
    }

    private func remove(_ contact: EmergencyContact) {
        store.saveContacts(contacts: settings.contacts.filter { $0.phoneNumber != contact.phoneNumber })
        reload()
    }

    private func reload() {
        settings = store.load()
        onChanged()
    }
}
