package com.freedomfighter.swissbikehike

/** The three ways of moving. The BRouter profile and the overlay set are fixed per mode: nothing to configure. */
enum class Mode(val profile: String) {
    BIKE("trekking"),
    WALK("hiking-mountain"),
    /** Public transport: the walks to and from the stops use the hiking profile. */
    TRANSIT("hiking-mountain");

    companion object {
        fun of(name: String?): Mode = entries.firstOrNull { it.name == name } ?: WALK
    }
}
