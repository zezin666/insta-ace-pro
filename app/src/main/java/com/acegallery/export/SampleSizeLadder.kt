package com.acegallery.export

/** Returns the next bounded-memory retry scale after [current], or null when the ladder is exhausted. */
fun nextSampleSize(current: Int): Int? = when (current) {
    1 -> 2
    2 -> 4
    else -> null
}