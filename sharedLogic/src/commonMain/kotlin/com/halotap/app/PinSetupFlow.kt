package com.halotap.app

/** Where first-run setup has got to. */
sealed interface PinSetupState {

    /** [afterMismatch] is true when the two entries disagreed and the user must start again. */
    data class ChoosePin(val afterMismatch: Boolean = false) : PinSetupState

    data object ConfirmPin : PinSetupState

    /** The PIN is set. Offering the optional duress code. */
    data object OfferDuress : PinSetupState

    /** [sameAsPin] is true when the last attempt matched the disarm PIN and was refused. */
    data class ChooseDuress(val sameAsPin: Boolean = false) : PinSetupState

    data class Complete(val secret: DisarmSecret, val duress: DisarmSecret?) : PinSetupState
}

/**
 * First-run setup, as a state machine both watches drive.
 *
 * The rules here are small but easy to get subtly wrong, and getting them wrong on one
 * platform only is exactly what happened with the attempt count. Keeping them in one tested
 * place means Wear OS and watchOS cannot disagree about what setup does.
 *
 * Deliberately holds no I/O: the caller saves the secrets when [PinSetupState.Complete]
 * arrives. That is also why this is testable without a device.
 */
class PinSetupFlow(
    private val pinLength: Int = DEFAULT_PIN_LENGTH,
    /**
     * Whether to offer a duress code once the PIN is confirmed.
     *
     * False when changing only the PIN from settings: the duress code is edited separately
     * there, and forcing the user back through it would be a trap.
     */
    private val askForDuress: Boolean = true,
    /**
     * An already-chosen secret, which skips straight to the duress step.
     *
     * Used when changing only the duress code. It is needed rather than optional because the
     * new duress code has to be checked against the real PIN, and settings does not hold the
     * raw digits — only the secret.
     */
    existingSecret: DisarmSecret? = null,
) {

    var state: PinSetupState =
        if (existingSecret != null) PinSetupState.ChooseDuress() else PinSetupState.ChoosePin()
        private set

    private var firstEntry: String? = null
    private var chosen: DisarmSecret? = existingSecret

    /**
     * Feed a completed entry.
     *
     * Entries of the wrong length are ignored rather than rejected: the UI only calls this
     * once enough digits have been typed, so a wrong length is a caller bug, not user input.
     */
    fun submit(entry: String): PinSetupState {
        if (entry.length != pinLength) return state

        when (state) {
            is PinSetupState.ChoosePin -> {
                firstEntry = entry
                state = PinSetupState.ConfirmPin
            }

            is PinSetupState.ConfirmPin -> {
                if (entry == firstEntry) {
                    chosen = DisarmSecret.ofPin(entry)
                    state = if (askForDuress) {
                        PinSetupState.OfferDuress
                    } else {
                        PinSetupState.Complete(secret = DisarmSecret.ofPin(entry), duress = null)
                    }
                } else {
                    // Start over rather than letting them retry the confirmation: when the
                    // two differ, which one was the typo is unknowable.
                    firstEntry = null
                    state = PinSetupState.ChoosePin(afterMismatch = true)
                }
            }

            is PinSetupState.ChooseDuress -> {
                val secret = requireNotNull(chosen) { "a secret must be chosen before a duress code" }
                state = if (secret.matches(entry)) {
                    // The disarm branch is checked first when disarming, so an identical
                    // duress code could never fire. Refusing beats handing the user one
                    // that silently does nothing.
                    PinSetupState.ChooseDuress(sameAsPin = true)
                } else {
                    PinSetupState.Complete(secret = secret, duress = DisarmSecret.ofPin(entry))
                }
            }

            is PinSetupState.OfferDuress, is PinSetupState.Complete -> Unit
        }
        return state
    }

    /** From [PinSetupState.OfferDuress]: the user wants a duress code. */
    fun beginDuress(): PinSetupState {
        if (state is PinSetupState.OfferDuress) state = PinSetupState.ChooseDuress()
        return state
    }

    /** From [PinSetupState.OfferDuress]: finish without one. Most users will. */
    fun skipDuress(): PinSetupState {
        if (state is PinSetupState.OfferDuress) {
            state = PinSetupState.Complete(secret = requireNotNull(chosen), duress = null)
        }
        return state
    }

    companion object {
        const val DEFAULT_PIN_LENGTH: Int = 4
    }
}
