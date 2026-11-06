package org.openmotion.editor.ui.components

import java.util.Locale

/**
 * Utility to format video playback timestamps into standard SMPTE timecode (MM:SS:FF).
 */
object TimecodeFormatter {
    fun format(timeMs: Long, fps: Int = 60): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val remainderMs = timeMs % 1000
        val frames = (remainderMs * fps / 1000).toInt()

        return String.format(Locale.US, "%02d:%02d:%02d", minutes, seconds, frames)
    }
}
