import XCTest
import Contacts
import SharedLogic
@testable import Halotap

/// Turning address-book entries into something Halotap can text.
///
/// Tested on the transformation rather than the fetch, because a fetch needs a permission
/// prompt no test can answer. These are the cases that decide whether a real person gets a
/// message.
final class ContactsAccessTests: XCTestCase {

    private func contact(
        given: String = "",
        family: String = "",
        numbers: [String] = []
    ) -> CNMutableContact {
        let contact = CNMutableContact()
        contact.givenName = given
        contact.familyName = family
        contact.phoneNumbers = numbers.map {
            CNLabeledValue(label: CNLabelPhoneNumberMobile, value: CNPhoneNumber(stringValue: $0))
        }
        return contact
    }

    func testAFullNameAndNumberConvert() {
        let result = ContactsAccess.emergencyContact(
            from: contact(given: "Ada", family: "Lovelace", numbers: ["+1 555 000 1"])
        )
        XCTAssertEqual(result?.name, "Ada Lovelace")
        XCTAssertEqual(result?.phoneNumber, "+15550001")
    }

    func testFormattingIsStrippedFromTheNumber() {
        // SMS and tel: want digits. An address book is full of (555) 000-0001.
        let result = ContactsAccess.emergencyContact(
            from: contact(given: "Grace", numbers: ["(555) 000-0002 ext."])
        )
        XCTAssertEqual(result?.phoneNumber, "5550000002")
    }

    func testAGivenNameAloneIsEnough() {
        XCTAssertEqual(
            ContactsAccess.emergencyContact(from: contact(given: "Ada", numbers: ["+1"]))?.name,
            "Ada"
        )
    }

    func testAFamilyNameAloneIsEnough() {
        XCTAssertEqual(
            ContactsAccess.emergencyContact(from: contact(family: "Lovelace", numbers: ["+1"]))?.name,
            "Lovelace"
        )
    }

    func testANamelessEntryFallsBackToItsNumber() {
        // Dropping it would silently lose someone the user may need.
        let result = ContactsAccess.emergencyContact(from: contact(numbers: ["+15550003"]))
        XCTAssertEqual(result?.name, "+15550003")
        XCTAssertEqual(result?.phoneNumber, "+15550003")
    }

    func testAnEntryWithNoNumberIsSkipped() {
        XCTAssertNil(ContactsAccess.emergencyContact(from: contact(given: "Ada")))
    }

    func testAnEntryWhoseNumberHasNoDigitsIsSkipped() {
        // EmergencyContact rejects a blank number, so this would otherwise throw and take
        // the whole enumeration with it.
        XCTAssertNil(ContactsAccess.emergencyContact(from: contact(given: "Ada", numbers: ["---"])))
    }

    func testTheFirstNumberIsUsedWhenThereAreSeveral() {
        let result = ContactsAccess.emergencyContact(
            from: contact(given: "Ada", numbers: ["+15550001", "+15550099"])
        )
        XCTAssertEqual(result?.phoneNumber, "+15550001")
    }

    func testStatusCanBeReadWithoutPrompting() {
        // The reason load() checks status first: a prompt in a test run never gets answered.
        XCTAssertNotNil(ContactsAccess.status())
    }
}
