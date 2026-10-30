import CallKit
import Combine
import SwiftUI
import SharedLogic

/// Placeholder secret.
///
/// A real one is chosen by the user during onboarding and kept in the Keychain, never
/// compiled in. This mirrors the same TODO on the Wear OS side.
/// The platform channel is always present; a user-configured one is appended, never
/// substituted, so a mistyped webhook costs a redundant alert rather than the alarm.
///
/// On Apple Watch the built-in channel can only place a call, and only to one contact --
/// watchOS cannot send a text at all. Configuring the bring-your-own channel therefore
/// matters considerably more here than it does on Wear OS.
private func makeEscalator() -> Escalator {
    Escalator(
        channels: [
            // Both arguments spelled out because Kotlin default values do not cross into
            // Swift -- the same reason the attempt count silently drifted apart once.
            CallEscalationChannel(
                callObserver: CXCallObserver(),
                id: CallEscalationChannel.companion.ID
            ),
            HttpEscalationChannel(
                poster: DarwinHttpPoster(session: .shared),
                config: userHttpConfig(),
                id: HttpEscalationChannel.companion.ID
            ),
        ],
        policy: .allChannels
    )
}

/// Nil until onboarding exists, which makes the HTTP channel report NotConfigured.
private func userHttpConfig() -> HttpEscalationConfig? { nil }

/// Monotonic milliseconds. Deliberately not `Date()`: the wall clock can be changed, and a
/// user-settable clock must not be able to push the challenge deadline out of reach.
private func nowMillis() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
}

struct StandbyView: View {
    let secret: DisarmSecret
    let duressSecret: DisarmSecret?
    let contacts: [EmergencyContact]
    let store: SettingsStore
    let onSettingsChanged: () -> Void

    // Both built in init() so an interrupted hold can be restored before the first frame.
    // The attempt and grace values come from the shared defaults rather than being written
    // out: Kotlin default arguments do not cross into Swift, which is how the two watches
    // silently drifted apart once already.
    @State private var session: StandbySession
    @State private var state: any StandbyState
    @State private var entry = ""
    @State private var isHolding = false
    @Environment(\.scenePhase) private var scenePhase
    @State private var escalator = makeEscalator()
    @State private var markers: ArmedMarkerStore
    @State private var showingSettings = false
    @State private var scheduler = BreachScheduler()
    @State private var tokens = UserDefaultsBreachTokens(defaults: .standard)
    @State private var attemptSummary: String?
    // Whether the alert screen has swapped its banner for the keypad. Local to the view:
    // standing down is an affordance over the session's own check, not a state of its own.
    @State private var clearingAlert = false

    // Drives the challenge deadline, so that waiting in silence still escalates.
    private let heartbeat = Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()

    /// Restores an interrupted hold before the first frame.
    ///
    /// An incoming call tears the app down, which would otherwise drop the user out of
    /// standby silently — no false alarm, but no protection either. Done here rather than in
    /// `.task` so the screen never flashes HOLD on its way to ON WATCH.
    init(
        secret: DisarmSecret,
        duressSecret: DisarmSecret?,
        contacts: [EmergencyContact],
        store: SettingsStore,
        onSettingsChanged: @escaping () -> Void
    ) {
        self.secret = secret
        self.duressSecret = duressSecret
        self.contacts = contacts
        self.store = store
        self.onSettingsChanged = onSettingsChanged

        let store = UserDefaultsArmedMarkerStore(defaults: .standard)
        // Every argument spelled out: Kotlin default values do not cross into Swift, which
        // is how the two watches silently drifted to different attempt counts once already.
        let newSession = StandbySession(
            secret: secret,
            maxAttempts: StandbySession.companion.DEFAULT_MAX_ATTEMPTS,
            graceMillis: StandbySession.companion.DEFAULT_GRACE_MILLIS,
            duressSecret: duressSecret
        )

        var initial: any StandbyState = newSession.state
        if let saved = store.load()?.int64Value {
            if StandbySession.companion.isArmedMarkerUsable(
                savedSinceMillis: saved,
                nowMillis: nowMillis(),
                maxAgeMillis: StandbySession.companion.MAX_RESTORE_AGE_MILLIS
            ) {
                initial = newSession.restoreArmed(heldSinceMillis: saved)
            } else {
                // Rebooted, or older than the window. Drop it rather than reconsidering it
                // on every launch.
                store.save(sinceMillis: nil)
            }
        }

        _session = State(initialValue: newSession)
        _markers = State(initialValue: store)
        _state = State(initialValue: initial)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // Switched on appearance, not state. A duress breach must be drawn exactly as a
            // real disarm: anything that lets an onlooker tell them apart defeats it.
            if let challenge = state as? StandbyStateChallenge {
                pinPad(challenge: challenge)
            } else if StandbyAppearanceKt.appearance(state) == .disarmed {
                banner(headline: "Disarmed", detail: "Stay safe", tint: .green)
                    // The Wear app closes itself here. watchOS cannot: WKApplication exposes
                    // no terminate, and exit() is forbidden by the HIG. Returning to Idle is
                    // the honest equivalent — the confirmation still shows, then the watch is
                    // ready for the next walk rather than stranded on a dead-end screen, and
                    // the system backgrounds the app on wrist-down.
                    //
                    // A plain .task, not .task(id:), because Disarmed is a Kotlin data object
                    // and therefore the same instance every time; an id keyed on it would
                    // never change and would fire only once per app launch. Appearing is the
                    // right trigger, and this branch is torn down on reset.
                    // Clears exactly as a real disarm does, after the same delay. The
                    // escalation for a duress breach was already started by the effect
                    // below and is deliberately not cancelled here.
                    .task(id: ObjectIdentifier(state as AnyObject)) {
                        try? await Task.sleep(for: .seconds(0.6))
                        entry = ""
                        attemptSummary = nil
                        markers.save(sinceMillis: nil)
                        if StandbyAppearanceKt.shouldStandDown(state) {
                            tokens.clearOpen()
                            scheduler.standDown()
                        }
                        state = session.reset()
                    }
            } else if let breached = state as? StandbyStateBreached, StandbyAppearanceKt.appearance(state) == .alert {
                // The alert has already gone out, so the keypad here is not a second chance
                // at the challenge -- it is the way back to a usable watch. Without it the
                // app is stranded: the crown only suspends, so reopening resumes this very
                // screen and there is nothing the user can "close".
                Group {
                    if clearingAlert {
                        PinKeypad(
                            entry: entry,
                            caption: "PIN to stand down",
                            captionTint: .gray,
                            onDigit: clearAlert,
                            onBackspace: { entry = String(entry.dropLast()) }
                        )
                    } else {
                        banner(
                            headline: "Alert",
                            detail: attemptSummary ?? "Alerting contacts\u{2026}",
                            tint: .red,
                            actionLabel: "I'm safe",
                            onAction: { clearingAlert = true }
                        )
                    }
                }
                // .task is keyed on the state, so this runs once per breach rather than on
                // every redraw. Raising the same alarm twice would double-call a contact.
                .task(id: ObjectIdentifier(breached)) { await escalate(breached) }
            } else {
                holdPad(armed: state is StandbyStateArmed)

                // Only while idle. Armed, the whole screen is a hold target and a stray tap
                // must never open a menu -- and nobody should edit contacts mid-walk.
                if state is StandbyStateIdle {
                    VStack {
                        HStack {
                            Spacer()
                            Button { showingSettings = true } label: {
                                Image(systemName: "gearshape.fill")
                                    .font(.system(size: 12))
                                    .foregroundColor(.gray)
                            }
                            .buttonStyle(.plain)
                        }
                        Spacer()
                    }
                    .padding(.trailing, 6)
                }
            }
        }
        .task {
            // The app may have been relaunched by the scheduled session, by a notification
            // tap, or by the user. Whichever it was, a challenge may have passed its
            // deadline while nothing was running.
            scheduler.onSessionStarted = { escalateMissedBreach() }
            escalateMissedBreach()
        }
        .sheet(isPresented: $showingSettings) {
            SettingsView(store: store, onChanged: onSettingsChanged)
        }
        .onReceive(heartbeat) { _ in
            if state is StandbyStateChallenge {
                state = session.tick(nowMillis: nowMillis())
            }
        }
    }

    // MARK: - Screens

    private func holdPad(armed: Bool) -> some View {
        VStack(spacing: 6) {
            Text(armed ? "ON WATCH" : "HOLD")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(armed ? .orange : .gray)
            Text(armed ? "Release when safe" : "Press and hold to arm")
                .font(.system(size: 12))
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        // A zero-distance drag is the only reliable way to observe press and release
        // separately; LongPressGesture reports neither edge on its own.
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !isHolding else { return }
                    isHolding = true
                    state = session.hold(nowMillis: nowMillis())
                    if let armed = state as? StandbyStateArmed {
                        markers.save(sinceMillis: KotlinLong(value: armed.heldSinceMillis))
                    }
                }
                .onEnded { _ in
                    isHolding = false
                    // Only a genuine lift releases. SwiftUI has no gesture-cancelled
                    // callback, so the scene phase stands in for one: if the app is not
                    // active, the system took the screen -- an incoming call -- and the
                    // user has not let go. Releasing then would start a silent challenge
                    // behind the call UI and breach ten seconds later.
                    guard scenePhase == .active else { return }
                    state = session.release(nowMillis: nowMillis())

                    // Hand the deadline to the system before anything else, so the breach
                    // can still happen if this app does not survive the next ten seconds.
                    if let challenge = state as? StandbyStateChallenge {
                        tokens.open(token: challenge.deadlineMillis)
                        scheduler.challengeOpened(
                            deadline: Date().addingTimeInterval(
                                Double(challenge.deadlineMillis - nowMillis()) / 1000
                            )
                        )
                    }
                    // The hold is over however this ended. Left behind, the marker would
                    // re-arm the app on its next launch.
                    if !(state is StandbyStateArmed) { markers.save(sinceMillis: nil) }
                }
        )
    }

    private func pinPad(challenge: StandbyStateChallenge) -> some View {
        PinKeypad(
            entry: entry,
            caption: challenge.attemptsRemaining > 1
                ? "\(challenge.attemptsRemaining) tries left"
                : "Last try",
            captionTint: challenge.attemptsRemaining > 1 ? .gray : .red,
            onDigit: append,
            onBackspace: { entry = String(entry.dropLast()) }
        )
    }

    private func banner(
        headline: String,
        detail: String,
        tint: Color,
        actionLabel: String? = nil,
        onAction: (() -> Void)? = nil
    ) -> some View {
        VStack(spacing: 6) {
            Text(headline)
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(tint)
            Text(detail)
                .font(.system(size: 12))
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
            if let actionLabel, let onAction {
                Button(actionLabel, action: onAction)
                    .font(.system(size: 12))
                    .padding(.top, 4)
            }
        }
    }


    // MARK: - Missed breaches

    /// Escalates a challenge whose deadline passed while the app was not running.
    ///
    /// Only when it passed recently. Alerting somebody's emergency contacts about a walk
    /// that ended hours ago is how an app teaches people to ignore it — the same reasoning
    /// as the Wear boot receiver, which notifies rather than escalates.
    private func escalateMissedBreach() {
        let action = MissedBreach.shared.actionFor(
            openDeadlineMillis: tokens.openToken(),
            handledDeadlineMillis: tokens.handledToken(),
            nowMillis: nowMillis(),
            maxLateMillis: MissedBreach.shared.MAX_LATE_ESCALATION_MILLIS
        )

        guard action == .escalate else {
            if action == .notifyOnly { attemptSummary = "A breach was missed while closed" }
            return
        }

        let breached = StandbyStateBreached(reason: .deadlinePassed)
        state = breached
        Task { await escalate(breached) }
    }

    // MARK: - Escalation

    private func escalate(_ breached: StandbyStateBreached) async {
        let context = BreachContext(
            reason: breached.reason,
            occurredAtMillis: nowMillis(),
            contacts: contacts,
            message: BreachMessages.shared.default(reason: breached.reason)
        )
        markers.save(sinceMillis: nil)
        tokens.markHandled(token: tokens.openToken())
        scheduler.standDown()
        do {
            let attempts = try await escalator.escalate(context: context)
            attemptSummary = summarize(attempts)
        } catch {
            attemptSummary = "Could not reach contacts"
        }
    }

    private func summarize(_ attempts: [EscalationAttempt]) -> String {
        let delivered = attempts.filter { $0.outcome is EscalationOutcomeDelivered }
        if !delivered.isEmpty {
            return "Contacts alerted via " + delivered.map(\.channelId).joined(separator: ", ")
        }
        // Ranked below Delivered on purpose: openSystemURL reports nothing back, so saying
        // the contact was reached would be a guess presented as a fact. This is the usual
        // outcome on Apple Watch, where the built-in channel can only dial.
        if attempts.contains(where: { $0.outcome is EscalationOutcomeUnconfirmed }) {
            return "Alert sent, delivery unconfirmed"
        }
        if attempts.allSatisfy({ $0.outcome is EscalationOutcomeNotConfigured }) {
            return "No alert channel set up"
        }
        return "Could not reach contacts"
    }

    // MARK: - Entry

    private func append(_ digit: Character) {
        entry.append(digit)
        guard entry.count >= pinLength else { return }
        state = session.submit(entry: entry, nowMillis: nowMillis())
        entry = ""
    }

    /// The alert screen's keypad. A wrong code leaves the alert standing; unlike the
    /// challenge there is nothing worse left to trigger, so it can be retried freely.
    private func clearAlert(_ digit: Character) {
        entry.append(digit)
        guard entry.count >= pinLength else { return }
        state = session.clearBreach(entry: entry)
        entry = ""
        guard !(state is StandbyStateBreached) else { return }
        clearingAlert = false
        attemptSummary = nil
        tokens.clearOpen()
        markers.save(sinceMillis: nil)
    }
}
