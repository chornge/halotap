package com.halotap.app.settings

import com.halotap.app.BreachReason

/**
 * The last alert this watch sent.
 *
 * Kept because the alert screen was the only sign an alert had ever happened, and that screen
 * can be made to go away: by standing down with the PIN, or simply by force-quitting the app.
 * Without this, someone who took the watch could leave the owner with no way to tell whether
 * their contacts were ever told. The record is written when the alert goes out and there is
 * deliberately no API to erase it — clearing the screen is cosmetic, this is the fact.
 *
 * It holds no phone numbers, no message body and nothing about who was contacted: enough to
 * answer "did an alert go out, when, and did it get through", and no more. Anyone who can read
 * this can already read the settings it sits beside.
 */
data class AlertRecord(
    /**
     * Wall-clock milliseconds since the epoch, passed in by the caller.
     *
     * Wall clock rather than the monotonic clock the rest of the session uses, because this one
     * is for showing a person a time. It is never used to decide anything.
     */
    val occurredAtMillis: Long,
    val reason: BreachReason,
    /** What the escalation reported, in the same words the alert screen showed. */
    val detail: String,
)

/**
 * The stored form of an [AlertRecord].
 *
 * One string, so each platform stores a single value with its own key-value API and the format
 * cannot drift between them — the same reasoning as the rest of [SettingsStore], one level down.
 */
object AlertRecords {

    fun encode(record: AlertRecord): String =
        "${record.occurredAtMillis}$SEPARATOR${record.reason.name}$SEPARATOR${record.detail}"

    /**
     * Null for anything that is not a record this version wrote — a value from an older build,
     * or a reason that no longer exists. Returning null shows "no alert recorded", which is
     * wrong in a visible way; guessing at a half-parsed record would be wrong in a quiet one.
     */
    fun decode(raw: String): AlertRecord? {
        val occurredAt = raw.substringBefore(SEPARATOR).toLongOrNull() ?: return null
        val rest = raw.substringAfter(SEPARATOR, missingDelimiterValue = "")
        val reason = reasonNamed(rest.substringBefore(SEPARATOR)) ?: return null
        return AlertRecord(
            occurredAtMillis = occurredAt,
            reason = reason,
            // Everything after the second separator, so a detail containing one survives.
            detail = rest.substringAfter(SEPARATOR, missingDelimiterValue = ""),
        )
    }

    /** A [BreachReason] by its stored name, or null if it is not one. */
    fun reasonNamed(name: String): BreachReason? =
        BreachReason.entries.firstOrNull { it.name == name }

    private const val SEPARATOR = '|'
}
