import Contacts
import SharedLogic

/// Reading the watch's own contacts.
///
/// watchOS ships no `ContactsUI`, so there is no system picker to present — but
/// `Contacts.framework` itself is fully available, with no `API_UNAVAILABLE(watchos)` on
/// `CNContactStore`. So the watch can read the address book and draw its own list, which is
/// why the Apple side needs no companion app for setup. Wear OS genuinely cannot do this.
///
/// Contacts on a watch are a subset synced from a paired iPhone. A watch that has never been
/// paired will legitimately return nothing, and the caller must say so plainly rather than
/// spinning forever — see `ContactsAccess.Result.empty`.
enum ContactsAccess {

    enum Result {
        case allowed([EmergencyContact])
        /// Access granted, but there is nothing to show. Usually a never-paired watch.
        case empty
        case denied
        case failed(String)
    }

    /// Current authorisation, without prompting. Asking first means a test, or a caller
    /// that merely wants to know, never triggers a dialog it cannot answer.
    static func status() -> CNAuthorizationStatus {
        CNContactStore.authorizationStatus(for: .contacts)
    }

    static func load() async -> Result {
        let store = CNContactStore()

        if status() == .notDetermined {
            let granted: Bool = await withCheckedContinuation { continuation in
                store.requestAccess(for: .contacts) { granted, _ in
                    continuation.resume(returning: granted)
                }
            }
            guard granted else { return .denied }
        } else if status() != .authorized {
            return .denied
        }

        let keys: [CNKeyDescriptor] = [
            CNContactGivenNameKey as CNKeyDescriptor,
            CNContactFamilyNameKey as CNKeyDescriptor,
            CNContactPhoneNumbersKey as CNKeyDescriptor,
        ]

        var found: [EmergencyContact] = []
        do {
            try store.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) { contact, _ in
                if let usable = emergencyContact(from: contact) { found.append(usable) }
            }
        } catch {
            return .failed(error.localizedDescription)
        }

        return found.isEmpty ? .empty : .allowed(found.sorted { $0.name < $1.name })
    }

    /// One address-book entry, as something Halotap can actually text.
    ///
    /// Separated from the fetch so it can be tested without a permission prompt, which a
    /// test cannot answer. Returns nil for anything unusable rather than throwing: one odd
    /// entry must not cost the user their whole contact list.
    static func emergencyContact(from contact: CNContact) -> EmergencyContact? {
        guard let number = contact.phoneNumbers.first?.value.stringValue else { return nil }

        // Address books hold spaces, dashes and parentheses; SMS and tel: want neither.
        let digits = number.filter { $0.isNumber || $0 == "+" }
        guard !digits.isEmpty else { return nil }

        let name = [contact.givenName, contact.familyName]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        // An entry with a number but no name is real; falling back to the number beats
        // dropping someone the user may need.
        return EmergencyContact(name: name.isEmpty ? digits : name, phoneNumber: digits)
    }
}
