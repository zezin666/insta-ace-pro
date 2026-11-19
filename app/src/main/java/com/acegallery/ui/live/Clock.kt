package com.acegallery.ui.live

/** Injectable monotonic clock; production uses elapsed realtime, not wall time. */
fun interface Clock {
    fun nowMs(): Long
}
