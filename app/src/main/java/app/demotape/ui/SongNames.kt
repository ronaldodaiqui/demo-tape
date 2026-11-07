package app.demotape.ui

import kotlin.random.Random

/**
 * Made-up song titles, for when "10-29-2026 21-45" isn't inspiring. Built from small word lists
 * in a handful of shapes (Adjective Noun, The Noun of the Noun, Noun in Place…).
 */
object SongNames {
    private val adjectives = listOf(
        "Quiet", "Golden", "Paper", "Slow", "Electric", "Hollow", "Velvet", "Borrowed", "Crooked", "Midnight",
        "Wild", "Little", "Blue", "Rusty", "Silver", "Lonesome", "Sleepy", "Neon", "Faded", "Bright",
        "Broken", "Gentle", "Restless", "Northern", "Sunday", "Secondhand", "Tangled", "Distant", "Last", "Easy",
        "Burning", "Weightless", "Static", "Honey", "Salt", "Winter", "Summer", "Open", "Careless", "Phantom",
    )
    private val nouns = listOf(
        "Lantern", "River", "Highway", "Window", "Engine", "Garden", "Satellite", "Postcard", "Harbor", "Echo",
        "Kitchen", "Comet", "Porch", "Radio", "Ocean", "Lighthouse", "Thunder", "Mirror", "Train", "Orchard",
        "Feather", "Motel", "Ghost", "Valley", "Bicycle", "Cathedral", "Telephone", "Moon", "Carousel", "Wildfire",
        "Morning", "Heartbeat", "Canyon", "Letter", "Island", "Tide", "Parade", "Balloon", "Signal", "Hallway",
    )
    private val plurals = listOf(
        "Lanterns", "Rivers", "Headlights", "Windows", "Gardens", "Satellites", "Postcards", "Echoes", "Comets",
        "Radios", "Mirrors", "Trains", "Feathers", "Ghosts", "Valleys", "Letters", "Islands", "Tides", "Sparrows",
        "Fireflies", "Stars", "Waves", "Strangers", "Shadows", "Bells", "Wires", "Pines", "Clouds", "Embers",
    )
    private val places = listOf(
        "the Rain", "the Dark", "the Kitchen", "the Valley", "Bloom", "Reverse", "the Morning", "the Static",
        "Slow Motion", "the Deep End", "the Attic", "the Snow", "the Wire", "Orbit", "the Shallows", "the Afterglow",
    )
    private val verbs = listOf(
        "Waiting", "Running", "Falling", "Driving", "Dancing", "Leaving", "Burning", "Holding", "Chasing",
        "Floating", "Breaking", "Singing", "Drifting", "Calling", "Dreaming", "Counting",
    )
    private val people = listOf(
        "June", "Marie", "Jack", "Delilah", "Ruby", "Hank", "Josephine", "Lou", "Annie", "Rosa", "Eli", "Mabel",
    )

    fun random(r: Random = Random): String {
        fun <T> List<T>.pick() = this[r.nextInt(size)]
        return when (r.nextInt(9)) {
            0 -> "${adjectives.pick()} ${nouns.pick()}"
            1 -> "The ${nouns.pick()} of ${plurals.pick()}"
            2 -> "${nouns.pick()} in ${places.pick()}"
            3 -> "${verbs.pick()} for ${plurals.pick()}"
            4 -> "${adjectives.pick()} ${plurals.pick()}"
            5 -> "${people.pick()} and the ${nouns.pick()}"
            6 -> "${verbs.pick()} ${places.pick().removePrefix("the ").let { "Through the $it" }}"
            7 -> "${plurals.pick()} & ${plurals.pick()}"
            else -> "${adjectives.pick()} ${nouns.pick()} ${listOf("Blues", "Song", "Waltz", "Lullaby", "Serenade", "Anthem", "Ballad").pick()}"
        }
    }
}
