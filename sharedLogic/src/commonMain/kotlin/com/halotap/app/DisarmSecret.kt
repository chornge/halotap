package com.halotap.app

/**
 * The secret that disarms a standby session.
 *
 * A PIN and a swipe pattern are the same thing once entered: an ordered sequence of symbols.
 * Encoding both to one canonical string means the session logic never has to care which the
 * user chose, and a user can switch between them without a second code path.
 */
class DisarmSecret private constructor(
    /**
     * The canonical encoding, exposed solely so a [com.halotap.app.settings.SettingsStore]
     * can persist it. Never log it, never show it, and never put it in a crash report.
     */
    val encoded: String,
) {

    /**
     * True when [entry] is the secret.
     *
     * The comparison is constant-time over the digits so that a wrong entry takes the same
     * time as a right one. Length still leaks, which is accepted: an attacker holding the
     * watch can see the input UI anyway.
     */
    fun matches(entry: String): Boolean {
        if (entry.length != encoded.length) return false
        var difference = 0
        for (index in encoded.indices) {
            difference = difference or (encoded[index].code xor entry[index].code)
        }
        return difference == 0
    }

    companion object {
        const val MIN_PIN_LENGTH: Int = 4
        const val MAX_PIN_LENGTH: Int = 12
        const val MIN_PATTERN_NODES: Int = 4

        /** A numeric PIN of [MIN_PIN_LENGTH]..[MAX_PIN_LENGTH] digits. */
        fun ofPin(digits: String): DisarmSecret {
            require(digits.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
                "A PIN must be $MIN_PIN_LENGTH to $MAX_PIN_LENGTH digits, was ${digits.length}"
            }
            require(digits.all { it.isDigit() }) { "A PIN must contain digits only" }
            return DisarmSecret(digits)
        }

        /**
         * A swipe pattern, given as the node indices it visits in order.
         *
         * Nodes may repeat only if not adjacent in the sequence, because a pattern cannot
         * stay on the same node twice in a row.
         */
        fun ofPattern(nodes: List<Int>): DisarmSecret {
            require(nodes.size >= MIN_PATTERN_NODES) {
                "A pattern must visit at least $MIN_PATTERN_NODES nodes, was ${nodes.size}"
            }
            require(nodes.all { it >= 0 }) { "Pattern node indices must not be negative" }
            require(nodes.zipWithNext().none { (a, b) -> a == b }) {
                "A pattern cannot visit the same node twice in a row"
            }
            return DisarmSecret(nodes.joinToString(separator = "-"))
        }

        /** Encodes an in-progress entry the same way [ofPattern] encodes a stored pattern. */
        fun encodePatternEntry(nodes: List<Int>): String = nodes.joinToString(separator = "-")

        /** Rebuilds a secret from [encoded], for loading one back out of storage. */
        fun ofEncoded(encoded: String): DisarmSecret {
            require(encoded.isNotBlank()) { "A stored secret cannot be blank" }
            return DisarmSecret(encoded)
        }
    }
}
