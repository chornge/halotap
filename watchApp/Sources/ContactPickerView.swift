import SwiftUI
import SharedLogic

/// The watch's own contact list, since watchOS has no system picker.
///
/// Every state says what is actually true. A never-paired watch has no contacts, and telling
/// the user that is far better than a list that never loads — they would otherwise believe
/// setup had succeeded.
struct ContactPickerView: View {
    let onPicked: (EmergencyContact) -> Void

    @State private var result: ContactsAccess.Result?

    var body: some View {
        Group {
            switch result {
            case .none:
                ProgressView()

            case .allowed(let contacts):
                List(contacts, id: \.phoneNumber) { contact in
                    Button {
                        onPicked(contact)
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(contact.name).font(.system(size: 14))
                            Text(contact.phoneNumber)
                                .font(.system(size: 11))
                                .foregroundColor(.gray)
                        }
                    }
                }

            case .empty:
                notice(
                    "No contacts",
                    "This watch has none to choose from. Contacts sync from a paired iPhone."
                )

            case .denied:
                notice(
                    "No access",
                    "Halotap cannot read your contacts. Allow access in Settings, or it will have nobody to alert."
                )

            case .failed(let reason):
                notice("Could not read contacts", reason)
            }
        }
        .task { result = await ContactsAccess.load() }
    }

    private func notice(_ title: String, _ detail: String) -> some View {
        VStack(spacing: 6) {
            Text(title).font(.system(size: 16, weight: .bold)).foregroundColor(.orange)
            Text(detail)
                .font(.system(size: 11))
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 8)
    }
}
