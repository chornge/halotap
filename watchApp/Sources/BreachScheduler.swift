import Foundation
import UserNotifications
import WatchKit
import SharedLogic

/// Arranging for a breach to happen when the app may not be running.
///
/// watchOS has no `AlarmManager`. The two mechanisms it does have are both partial, so both
/// are used:
///
/// 1. **A scheduled extended runtime session** relaunches the app at the deadline — but only
///    if the user has made Halotap the default responder for its session type. When they have
///    not, `startAtDate:` fails with `notApprovedToSchedule` and nothing happens.
/// 2. **A local notification** fires regardless, even with the app dead. It cannot run code,
///    so it cannot send an alert by itself; it buzzes the wrist and, if tapped, opens the app,
///    which then escalates on the way in.
///
/// Neither matches Wear OS, where an exact alarm wakes a receiver and a worker sends the
/// message whether or not anyone is looking. On Apple Watch a breach is only guaranteed to be
/// *noticed*, not *sent*, unless the app happens to be alive or the user is authorised for
/// scheduled sessions. That limitation is documented rather than papered over.
final class BreachScheduler: NSObject, WKExtendedRuntimeSessionDelegate {

    private var session: WKExtendedRuntimeSession?

    /// Called when a scheduled session actually relaunched us, so the caller can escalate.
    var onSessionStarted: (() -> Void)?

    func challengeOpened(deadline: Date) {
        scheduleSession(at: deadline)
        scheduleNotification(at: deadline)
    }

    func standDown() {
        session?.invalidate()
        session = nil
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [Self.notificationID])
    }

    /// Asked for during setup, not at the deadline. A permission prompt appearing mid-breach
    /// would be both useless and dangerous.
    static func requestNotificationAuthorization() {
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Whether the system will relaunch the app at a scheduled time.
    static func scheduledSessionsAvailable() async -> Bool {
        await withCheckedContinuation { continuation in
            WKExtendedRuntimeSession.requestAutoLaunchAuthorizationStatus { status, _ in
                continuation.resume(returning: status == .active)
            }
        }
    }

    // MARK: - Mechanisms

    private func scheduleSession(at deadline: Date) {
        let session = WKExtendedRuntimeSession()
        session.delegate = self
        // Fails with notApprovedToSchedule when the user has not made this the default
        // responder. That is reported through the delegate rather than thrown, and the
        // notification below is what covers it.
        session.start(at: deadline)
        self.session = session
    }

    private func scheduleNotification(at deadline: Date) {
        let content = UNMutableNotificationContent()
        content.title = "Halotap"
        content.body = "You did not disarm. Open Halotap to send the alert."
        content.sound = .default

        let after = max(deadline.timeIntervalSinceNow, 1)
        let request = UNNotificationRequest(
            identifier: Self.notificationID,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: after, repeats: false)
        )

        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.notificationID])
        center.add(request)
    }

    // MARK: - WKExtendedRuntimeSessionDelegate

    func extendedRuntimeSessionDidStart(_ session: WKExtendedRuntimeSession) {
        // The app is awake because the deadline arrived. Buzz the wrist, then let the caller
        // send the alert.
        //
        // Which call does the buzzing matters. notifyUser(hapticType:) is ignored while the
        // app is in the foreground, and watchOS reads a scheduled session that played no
        // alarm as a misbehaving app: it offers the user a "disable this app's background
        // tasks" prompt, which would take the durability with it. So when the app is already
        // on screen -- the common case, since the deadline runs out while the user is
        // looking at the keypad -- play the haptic directly instead.
        if WKApplication.shared().applicationState == .active {
            WKInterfaceDevice.current().play(.notification)
        } else {
            session.notifyUser(hapticType: .notification)
        }
        onSessionStarted?()
    }

    func extendedRuntimeSessionWillExpire(_ session: WKExtendedRuntimeSession) {}

    func extendedRuntimeSession(
        _ session: WKExtendedRuntimeSession,
        didInvalidateWith reason: WKExtendedRuntimeSessionInvalidationReason,
        error: Error?
    ) {
        self.session = nil
    }

    private static let notificationID = "halotap.breach"
}
