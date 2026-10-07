/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.parser

import org.breezyweather.domain.multisource.model.TimestampedCue

/**
 * Standard-compliant parser for WebVTT subtitle tracks.
 */
object WebVttParser {

    private val TIMESTAMP_ARROW_REGEX = Regex("""^(\d{1,2}:)?(\d{2}):(\d{2})[.,](\d{3})\s+-->\s+(\d{1,2}:)?(\d{2}):(\d{2})[.,](\d{3})(?: .*)?$""")

    /**
     * Parses WebVTT text into a list of [TimestampedCue].
     * Throws [IllegalArgumentException] if the content is not valid WebVTT format.
     */
    fun parse(vttContent: String, model: String? = null): List<TimestampedCue> {
        val lines = vttContent.lineSequence().map { it.trimEnd('\r', '\n') }.toList()
        if (lines.isEmpty() || !lines.first().trim().startsWith("WEBVTT")) {
            throw IllegalArgumentException("Invalid WebVTT header: missing WEBVTT signature")
        }

        val cues = mutableListOf<TimestampedCue>()
        var index = 1 // skip WEBVTT line
        val totalLines = lines.size

        while (index < totalLines) {
            val line = lines[index].trim()

            // Skip empty lines
            if (line.isEmpty()) {
                index++
                continue
            }

            // Skip NOTE blocks
            if (line.startsWith("NOTE")) {
                while (index < totalLines && lines[index].trim().isNotEmpty()) {
                    index++
                }
                continue
            }

            // Check if this line is a timestamp line or an identifier followed by a timestamp
            val isArrow = TIMESTAMP_ARROW_REGEX.matches(line)
            val timingLine: String
            if (isArrow) {
                timingLine = line
                index++
            } else if (index + 1 < totalLines && TIMESTAMP_ARROW_REGEX.matches(lines[index + 1].trim())) {
                // lines[index] is cue ID, lines[index + 1] is timing line
                timingLine = lines[index + 1].trim()
                index += 2
            } else {
                // Unknown or unparseable line, advance
                index++
                continue
            }

            // Parse timestamps
            val match = TIMESTAMP_ARROW_REGEX.matchEntire(timingLine)
                ?: throw IllegalArgumentException("Malformed timing line: $timingLine")

            val startMs = parseTimestampGroup(
                hStr = match.groupValues[1],
                mStr = match.groupValues[2],
                sStr = match.groupValues[3],
                msStr = match.groupValues[4]
            )

            val endMs = parseTimestampGroup(
                hStr = match.groupValues[5],
                mStr = match.groupValues[6],
                sStr = match.groupValues[7],
                msStr = match.groupValues[8]
            )

            // Collect text lines until empty line or end
            val textLines = mutableListOf<String>()
            while (index < totalLines && lines[index].trim().isNotEmpty()) {
                textLines.add(lines[index].trim())
                index++
            }

            val text = textLines.joinToString("\n")
            if (text.isNotEmpty()) {
                cues.add(
                    TimestampedCue(
                        startMs = startMs,
                        endMs = endMs,
                        rawText = text,
                        normalizedText = text,
                        confidence = null,
                        model = model
                    )
                )
            }
        }

        return cues
    }

    private fun parseTimestampGroup(hStr: String, mStr: String, sStr: String, msStr: String): Long {
        val hours = if (hStr.isNotEmpty()) hStr.removeSuffix(":").toLong() else 0L
        val minutes = mStr.toLong()
        val seconds = sStr.toLong()
        val millis = msStr.toLong()
        return (hours * 3600000L) + (minutes * 60000L) + (seconds * 1000L) + millis
    }
}
