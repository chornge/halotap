import CallKit
import Combine
import SwiftUI
import SharedLogic

/// The channels a breach runs through.
///
/// The platform channel is always present; a user-configured one is appended, never
/// substituted, so a mistyped webhook costs a redundant alert rather than the alarm.
///
/// On Apple Watch the built-in channel can only place a call, and only to one contact --
/// watchOS cannot send a text at all. Configuring the bring-your-own channel therefore
/// matters considerably more here than it does on Wear OS.
///
/// `httpConfig` is read from the settings store at escalation time rather than captured once.
/// It used to be a hardcoded `nil`, which meant the channel reported `NotConfigured` no
/// matter what the Keychain held -- so a configured webhook would have been silently ignored
/// on the one platform that most needs it. There is still no watch UI that writes one; see
/// README.md.
private func makeEscalator(
    callObserver: CXCallObserver,
    httpConfig: HttpEscalationConfig?
) -> Escalator {
    Escalator(
        channels: [
            // Both arguments spelled out because Kotlin default values do not cross into
            // Swift -- the same reason the attempt count silently drifted apart once.
            CallEscalationChannel(
                callObserver: callObserver,
                id: CallEscalationChannel.companion.ID
            ),
            HttpEscalationChannel(
                poster: DarwinHttpPoster(session: .shared),
                config: httpConfig,
                id: HttpEscalationChannel.companion.ID
            ),
        ],
        policy: .allChannels
    )
}

/// Monotonic milliseconds. Deliberately not `Date()`: the wall clock can be changed, and a
/// user-settable clock must not be able to push the challenge deadline out of reach.
private func nowMillis() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
}

/// Milliseconds since the Unix epoch, for the one value that is reported rather than compared.
///
/// Every deadline in this app is monotonic so that changing the watch's time cannot move it.
/// `BreachContext.occurredAtMillis` is the exception: it leaves the device, in the webhook body
/// and in the user's own alert log, and "milliseconds since this watch last booted" means
/// nothing to whoever reads it. Passing `nowMillis()` there -- which this did -- sent Wear OS a
/// real timestamp and Apple Watch a number in the thousands for the same event.
private func epochMillis() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
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
    // Held across escalations rather than built per breach: CXCallObserver reports the
    // calls it has been observing, so a freshly made one may not know about a live call
    // yet -- and "is a call already in progress" is what stops the dial cutting off the
    // user talking to somebody who can help.
    @State private var callObserver = CXCallObserver()
    @State private var markers: ArmedMarkerStore
    @State private var showingSettings = false
    @State private var scheduler = BreachScheduler()
    @State private var tokens = UserDefaultsBreachTokens(defaults: .standard)
    @State private var attemptSummary: String?
    // Whether the alert screen has swapped its banner for the keypad. Local to the view:
    // standing down is an affordance over the session's own check, not a state of its own.
    @State private var clearingAlert = false
    /// Seconds before another stand-down code will be looked at. Zero when one will.
    @State private var standDownWait = 0

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

        // Named markerStore, not store: a local called `store` here shadowed the settings
        // store parameter for the rest of the initialiser, which is a trap waiting for the
        // next person who needs to read a setting in here.
        let markerStore = UserDefaultsArmedMarkerStore(defaults: .standard)
        // Every argument spelled out: Kotlin default values do not cross into Swift, which
        // is how the two watches silently drifted to different attempt counts once already.
        let newSession = StandbySession(
            secret: secret,
            maxAttempts: StandbySession.companion.DEFAULT_MAX_ATTEMPTS,
            graceMillis: StandbySession.companion.DEFAULT_GRACE_MILLIS,
            duressSecret: duressSecret,
            // Persisted, not held in the session: force-quitting the app used to hand back a
            // fresh set of stand-down attempts, which made the doubling delay worthless.
            penalties: UserDefaultsStandDownPenaltyStore(defaults: .standard)
        )

        var initial: any StandbyState = newSession.state
        if let saved = markerStore.load()?.int64Value {
            if StandbySession.companion.isArmedMarkerUsable(
                savedSinceMillis: saved,
                nowMillis: nowMillis(),
                maxAgeMillis: StandbySession.companion.MAX_RESTORE_AGE_MILLIS
            ) {
                initial = newSession.restoreArmed(heldSinceMillis: saved)
            } else {
                // Rebooted, or older than the window. Drop it rather than reconsidering it
                // on every launch.
                markerStore.save(sinceMillis: nil)
            }
        }

        _session = State(initialValue: newSession)
        _markers = State(initialValue: markerStore)
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
                    // Disarmed is a Kotlin data object, so the id below is the same value on
                    // every disarm. That is fine, and only because this branch is torn down
                    // when the session resets and built again on the next one: appearing is
                    // the real trigger. It must stay that way -- if this branch ever becomes
                    // persistent, the id stops changing and the reset fires once per launch.
                    //
                    // A duress breach clears exactly as a real disarm does, after the same
                    // delay. Its escalation is started by the task on the container and is
                    // deliberately not cancelled here.
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
            } else if StandbyAppearanceKt.appearance(state) == .alert {
                // The alert has already gone out, so the keypad here is not a second chance
                // at the challenge -- it is the way back to a usable watch. Without it the
                // app is stranded: the crown only suspends, so reopening resumes this very
                // screen and there is nothing the user can "close".
                Group {
                    if clearingAlert {
                        PinKeypad(
                            entry: entry,
                            caption: standDownWait > 0 ? "Wait \(standDownWait)s" : "PIN to stand down",
                            captionTint: standDownWait > 0 ? .red : .gray,
                            onDigit: clearAlert,
                            onBackspace: { entry = String(entry.dropLast()) }
                        )
                        // Only ticks while the keypad is up, which is only during an alert
                        // somebody is clearing.
                        .task(id: clearingAlert) {
                            while clearingAlert && !Task.isCancelled {
                                let left = session.standDownDelayMillis(nowMillis: nowMillis())
                                standDownWait = Int((Double(left) / 1000).rounded(.up))
                                try? await Task.sleep(for: .milliseconds(200))
                            }
                        }
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
        // The one place a breach is escalated from, and deliberately not inside a branch of
        // the view above. A duress breach is drawn as an ordinary "Disarmed" screen -- that is
        // the whole point of it -- so hanging the escalation off the red alert branch meant a
        // duress code showed a convincing disarm and sent nothing at all.
        //
        // Keyed on the breach's identity so it runs once per breach and not once per redraw.
        .task(id: breachIdentity) {
            guard let breached = state as? StandbyStateBreached else { return }
            // Unstructured on purpose. The duress screen clears itself after 0.6s, and a
            // child task would be cancelled along with it -- abandoning the dial partway
            // through the one case the duress code exists for. `escalate` dedupes on the
            // breach token, so an extra trigger cannot turn into an extra call.
            Task { await escalate(breached) }
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

    /// Identifies the breach being reported, so escalation runs once per breach.
    ///
    /// Nil whenever the session has not breached, which is what keeps the trigger task idle
    /// for the entire ordinary lifetime of the screen.
    private var breachIdentity: ObjectIdentifier? {
        (state as? StandbyStateBreached).map { ObjectIdentifier($0) }
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
            // Read from the session, not from the deadline directly: it owns the clamping,
            // and a wrong entry buys a fresh window that this has to refill for.
            remainingFraction: { session.remainingFraction(nowMillis: nowMillis()) },
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

        // Setting the state is the whole job: the task on the container escalates whatever
        // breach is current. Starting one here as well -- which this did -- ran two
        // escalations for one breach, and the second found a call already in progress,
        // recorded "Could not reach contacts" and overwrote the alert record of the call
        // that had actually gone through.
        state = StandbyStateBreached(reason: .deadlinePassed)
    }

    // MARK: - Escalation

    /// Raises the alarm for one breach, at most once.
    ///
    /// The guard is the same rule the Wear OS worker uses, and it is shared code so the two
    /// cannot drift: a breach carries its challenge deadline as a token, and the token is
    /// recorded before anything is dialled. watchOS needs it as much as Wear does, because a
    /// breach can be reported by the screen appearing, by a scheduled session relaunching the
    /// app, and by a relaunch that arrives while the screen is already up.
    private func escalate(_ breached: StandbyStateBreached) async {
        let token = tokens.openToken()
        guard !EscalationVerdicts.shared.isAlreadyHandled(
            token: token,
            handledToken: tokens.handledToken()
        ) else { return }
        // Recorded before the first suspension point, so a second trigger arriving while this
        // one is still dialling is turned away rather than placing its own call.
        tokens.markHandled(token: token)

        // One wall-clock reading for both the outgoing alert and the stored record, so a user
        // comparing the two is not told two different times for one breach.
        let occurredAt = epochMillis()
        let context = BreachContext(
            reason: breached.reason,
            occurredAtMillis: occurredAt,
            contacts: contacts,
            message: BreachMessages.shared.default(reason: breached.reason)
        )
        markers.save(sinceMillis: nil)
        scheduler.standDown()

        // Built here rather than held in state, so a channel the user configured after this
        // screen appeared is still used.
        let escalator = makeEscalator(
            callObserver: callObserver,
            httpConfig: store.load().http
        )
        do {
            let attempts = try await escalator.escalate(context: context)
            attemptSummary = summarize(attempts)
        } catch {
            attemptSummary = "Could not reach contacts"
        }
        // Written whatever the outcome, including "nothing configured": afterwards the user
        // needs to know an alert was raised and how it went, and standing down must not be
        // able to erase that.
        store.saveLastAlert(record: AlertRecord(
            occurredAtMillis: occurredAt,
            reason: breached.reason,
            detail: attemptSummary ?? ""
        ))
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
        // Swallowed rather than queued during the wait. Letting the digits land would spend
        // the attempt the moment the wait ended, on a code typed before it.
        guard standDownWait == 0 else { return }

        entry.append(digit)
        guard entry.count >= pinLength else { return }
        state = session.clearBreach(entry: entry, nowMillis: nowMillis())
        entry = ""
        guard !(state is StandbyStateBreached) else { return }
        clearingAlert = false
        attemptSummary = nil
        tokens.clearOpen()
        markers.save(sinceMillis: nil)
    }
}
