package com.acegallery.ui.live

/** Restart the preview once when packets stop for this long. */
const val LIVE_WATCHDOG_TIMEOUT_MS = 1_000L

/**
 * A mode switch can interrupt the camera's live stream. Restart it once, never twice,
 * and only while the stream is supposed to be running.
 */
fun shouldRestartLive(
    streaming: Boolean,
    nowMs: Long,
    lastPacketMs: Long,
    alreadyRestarted: Boolean,
): Boolean = streaming && !alreadyRestarted && nowMs - lastPacketMs >= LIVE_WATCHDOG_TIMEOUT_MS
