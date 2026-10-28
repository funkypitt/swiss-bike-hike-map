package com.freedomfighter.swissbikehike

/** The two activities. The BRouter profile and the overlay set are fixed per mode: nothing to configure. */
enum class Mode(val profile: String) {
    BIKE("trekking"),
    WALK("hiking-mountain");

    companion object {
        fun of(name: String?): Mode = entries.firstOrNull { it.name == name } ?: WALK
    }
}
