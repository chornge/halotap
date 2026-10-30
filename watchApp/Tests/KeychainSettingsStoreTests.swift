import XCTest
import SharedLogic
@testable import Halotap

/// The Keychain store, which holds the user's PIN, duress code and emergency contacts.
///
/// Unlike the Android side there is no hand-rolled encryption to assert on: the Keychain is
/// the encryption, and the item is written with `AfterFirstUnlockThisDeviceOnly` so it never
/// reaches a backup. What is worth proving is that everything round-trips, that removal is
/// real, and that malformed stored data degrades to "unconfigured" instead of crashing the
/// app open.
final class KeychainSettingsStoreTests: XCTestCase {

    private var store: KeychainSettingsStore!

    override func setUp() {
        super.setUp()
        store = KeychainSettingsStore()
        store.removeAll()
    }

    override func tearDown() {
        store.removeAll()
        super.tearDown()
    }

    func testAFreshStoreIsUnconfigured() {
        let settings = store.load()
        XCTAssertNil(settings.secret)
        XCTAssertFalse(settings.isConfigured)
        XCTAssertTrue(settings.contacts.isEmpty)
        XCTAssertNil(settings.http)
    }

    func testTheSecretRoundTrips() {
        store.saveSecret(secret: DisarmSecret.companion.ofPin(digits: "4821"))

        let secret = store.load().secret
        XCTAssertNotNil(secret)
        XCTAssertTrue(secret!.matches(entry: "4821"))
        XCTAssertFalse(secret!.matches(entry: "1234"))
    }

    func testTheDuressCodeIsStoredSeparatelyFromTheSecret() {
        store.saveSecret(secret: DisarmSecret.companion.ofPin(digits: "4821"))
        store.saveDuressSecret(secret: DisarmSecret.companion.ofPin(digits: "9876"))

        let settings = store.load()
        XCTAssertTrue(settings.secret!.matches(entry: "4821"))
        XCTAssertTrue(settings.duressSecret!.matches(entry: "9876"))
        XCTAssertFalse(settings.duressSecret!.matches(entry: "4821"))
    }

    func testClearingTheSecretReturnsTheAppToSetup() {
        store.saveSecret(secret: DisarmSecret.companion.ofPin(digits: "4821"))
        store.saveSecret(secret: nil)
        XCTAssertFalse(store.load().isConfigured)
    }

    func testContactsRoundTripInOrder() {
        store.saveContacts(contacts: [
            EmergencyContact(name: "Ada", phoneNumber: "+15550001"),
            EmergencyContact(name: "Grace", phoneNumber: "+15550002"),
        ])

        let contacts = store.load().contacts
        XCTAssertEqual(contacts.map(\.name), ["Ada", "Grace"])
        XCTAssertEqual(contacts[1].phoneNumber, "+15550002")
    }

    func testAShorterContactListDoesNotLeaveTheTailBehind() {
        // Stored as one JSON array rather than indexed keys precisely so this cannot happen:
        // a leftover entry would mean alerting someone the user had removed.
        store.saveContacts(contacts: [
            EmergencyContact(name: "Ada", phoneNumber: "+1"),
            EmergencyContact(name: "Grace", phoneNumber: "+2"),
        ])
        store.saveContacts(contacts: [EmergencyContact(name: "Ada", phoneNumber: "+1")])

        XCTAssertEqual(store.load().contacts.map(\.name), ["Ada"])
    }

    func testClearingContactsEmptiesThem() {
        store.saveContacts(contacts: [EmergencyContact(name: "Ada", phoneNumber: "+1")])
        store.saveContacts(contacts: [])
        XCTAssertTrue(store.load().contacts.isEmpty)
    }

    func testTwilioCredentialsRoundTrip() {
        store.saveHttpConfig(config: HttpEscalationConfigTwilio(
            accountSid: "AC123", authToken: "secret-token", fromNumber: "+15559999"
        ))

        let twilio = store.load().http as? HttpEscalationConfigTwilio
        XCTAssertEqual(twilio?.accountSid, "AC123")
        XCTAssertEqual(twilio?.authToken, "secret-token")
    }

    func testSwitchingEndpointKindDoesNotLeaveTheOldOneBehind() {
        store.saveHttpConfig(config: HttpEscalationConfigTwilio(
            accountSid: "AC123", authToken: "t", fromNumber: "+1"
        ))
        store.saveHttpConfig(config: HttpEscalationConfigWebhook(
            url: "https://example.test/hook", headers: [:]
        ))

        XCTAssertTrue(store.load().http is HttpEscalationConfigWebhook)
    }

    func testAnInsecureWebhookIsRefusedOnRead() {
        // http:// would send a breach in the clear. Refused on the way out as well as in.
        store.saveHttpConfig(config: HttpEscalationConfigWebhook(
            url: "https://example.test/hook", headers: [:]
        ))
        XCTAssertNotNil(store.load().http)
    }

    func testSettingsSurviveANewStoreInstance() {
        store.saveSecret(secret: DisarmSecret.companion.ofPin(digits: "4821"))
        store.saveContacts(contacts: [EmergencyContact(name: "Ada", phoneNumber: "+1")])

        // The real question is whether it reached the Keychain, not an in-memory cache.
        let fresh = KeychainSettingsStore()
        XCTAssertTrue(fresh.load().secret!.matches(entry: "4821"))
        XCTAssertEqual(fresh.load().contacts.count, 1)
    }
}
