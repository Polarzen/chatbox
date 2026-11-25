package dev.polarzen.chatbox

import java.time.LocalTime

/** Consent is a separate choice even for an already paired phone. Empty metrics stop sharing. */
internal data class WearableSettings(
    val metrics: Set<String> = emptySet(),
    val profile: String = "",
    val sid: String = "",
    val exerciseEvents: Boolean = true,
    val morningTask: Boolean = false,
    val notifyWorkout: Boolean = false,
    val notifyMorning: Boolean = false,
    val morningTime: String = "08:00",
    val windowMinutes: Int = 90,
    val source: String = "",
) {
    init {
        LocalTime.parse(morningTime)
        require(windowMinutes in 1..1440)
    }
}

internal data class WearableSyncState(
    val settings: WearableSettings = WearableSettings(),
    val lastSync: Long = 0,
    val statuses: Map<String, String> = emptyMap(),
    val sources: Map<String, Set<String>> = emptyMap(),
    val notice: String = "",
    val pending: Boolean = false,
)
