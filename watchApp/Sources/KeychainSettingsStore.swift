import Foundation
import Security
import SharedLogic

/// The watch's own settings, in the Keychain.
///
/// Conforms to the Kotlin `SettingsStore` protocol, so the shared code reads from it exactly
/// as the Wear app reads from its Keystore-backed one. Written in Swift rather than Kotlin
/// because the Keychain is a CoreFoundation API, and hand-rolling `SecItem` dictionaries
/// through Kotlin/Native interop would be far more code for the same result.
///
/// `kSecAttrAccessibleAfterFirstUnlock` rather than `WhenUnlocked`: the watch is locked while
/// on the wrist, and the disarm check has to work there. It never leaves the device —
/// `ThisDeviceOnly` keeps it out of any backup or restore to new hardware.
final class KeychainSettingsStore: NSObject, SettingsStore {

    private let service = "com.halotap.app.settings"

    private enum Key {
        static let secret = "secret"
        static let duress = "duress"
        static let contacts = "contacts"
        static let http = "http"
    }

    // MARK: - SettingsStore

    func load() -> HalotapSettings {
        HalotapSettings(
            secret: read(Key.secret).map { DisarmSecret.companion.ofEncoded(encoded: $0) },
            duressSecret: read(Key.duress).map { DisarmSecret.companion.ofEncoded(encoded: $0) },
            contacts: loadContacts(),
            http: loadHttp(),
            maxAttempts: StandbySession.companion.DEFAULT_MAX_ATTEMPTS,
            graceMillis: StandbySession.companion.DEFAULT_GRACE_MILLIS
        )
    }

    func saveSecret(secret: DisarmSecret?) {
        write(Key.secret, secret?.encoded)
    }

    func saveDuressSecret(secret: DisarmSecret?) {
        write(Key.duress, secret?.encoded)
    }

    /// Stored as one JSON array rather than indexed keys, so a shorter list can never leave
    /// the tail of a longer one behind — which would mean alerting someone the user removed.
    func saveContacts(contacts: [EmergencyContact]) {
        guard !contacts.isEmpty else { return write(Key.contacts, nil) }
        let rows = contacts.map { ["name": $0.name, "phone": $0.phoneNumber] }
        write(Key.contacts, json(from: rows))
    }

    func saveHttpConfig(config: HttpEscalationConfig?) {
        switch config {
        case let webhook as HttpEscalationConfigWebhook:
            write(Key.http, json(from: ["kind": "webhook", "url": webhook.url]))
        case let twilio as HttpEscalationConfigTwilio:
            write(Key.http, json(from: [
                "kind": "twilio",
                "sid": twilio.accountSid,
                "token": twilio.authToken,
                "from": twilio.fromNumber,
            ]))
        default:
            write(Key.http, nil)
        }
    }

    // MARK: - Decoding

    private func loadContacts() -> [EmergencyContact] {
        guard let raw = read(Key.contacts),
              let rows = decode(raw) as? [[String: String]] else { return [] }

        return rows.compactMap { row in
            guard let name = row["name"], let phone = row["phone"],
                  !name.isEmpty, !phone.isEmpty else { return nil }
            return EmergencyContact(name: name, phoneNumber: phone)
        }
    }

    private func loadHttp() -> HttpEscalationConfig? {
        guard let raw = read(Key.http),
              let fields = decode(raw) as? [String: String] else { return nil }

        switch fields["kind"] {
        case "webhook":
            guard let url = fields["url"], url.hasPrefix("https://") else { return nil }
            return HttpEscalationConfigWebhook(url: url, headers: [:])
        case "twilio":
            guard let sid = fields["sid"], let token = fields["token"],
                  let from = fields["from"] else { return nil }
            return HttpEscalationConfigTwilio(accountSid: sid, authToken: token, fromNumber: from)
        default:
            return nil
        }
    }

    private func json(from value: Any) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func decode(_ raw: String) -> Any? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    // MARK: - Keychain

    private func baseQuery(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func write(_ account: String, _ value: String?) {
        // Delete first rather than branching on add-versus-update: the Keychain has no upsert,
        // and a stale item would otherwise make SecItemAdd fail with errSecDuplicateItem and
        // silently leave the old value in place.
        SecItemDelete(baseQuery(account) as CFDictionary)

        guard let value else { return }
        var item = baseQuery(account)
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    private func read(_ account: String) -> String? {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let value = String(data: data, encoding: .utf8),
              !value.isEmpty else { return nil }
        return value
    }

    /// Everything this app stored. Used by the tests, and by a future "forget everything".
    func removeAll() {
        [Key.secret, Key.duress, Key.contacts, Key.http].forEach { write($0, nil) }
    }
}
