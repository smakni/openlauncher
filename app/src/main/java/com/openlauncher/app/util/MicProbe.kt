package com.openlauncher.app.util

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * Finds out whether the microphone can be read, and from which source.
 *
 * A recording that produces silence looks the same whatever the cause, and on a
 * head unit there are several: the MCU commonly holds the microphone muted
 * outside a call, and the audio sources do not share a route — MIC can stay
 * silent on hardware where VOICE_RECOGNITION works, because the vendor wires
 * the latter for its own voice control.
 *
 * So each source is opened in turn and the signal level measured. A source that
 * opens but reads pure zeros is a different problem from one that refuses to
 * open at all, and telling them apart is the whole point.
 */
object MicProbe {

    private val SOURCES = listOf(
        "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
        "MIC" to MediaRecorder.AudioSource.MIC,
        "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER
    )

    private val SAMPLE_RATES = listOf(48000, 44100, 16000, 8000)

    fun run(context: Context): String = buildString {
        append("Microphone probe\n")
        append("=".repeat(52)).append('\n')

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // Checked first because it explains a silent recording on its own, and it
        // is the one cause that can be undone from here rather than worked around.
        append("\nmicrophone muted : ${audio?.isMicrophoneMute}\n")
        append("mode             : ${audio?.mode}\n")
        append("wired headset    : ${runCatching {
            @Suppress("DEPRECATION") audio?.isWiredHeadsetOn
        }.getOrNull()}\n")
        append("bluetooth sco    : ${runCatching {
            @Suppress("DEPRECATION") audio?.isBluetoothScoOn
        }.getOrNull()}\n")

        append("\nsource                rate    state       level\n")
        for ((name, source) in SOURCES) {
            for (rate in SAMPLE_RATES) {
                append("%-20s %6d  ".format(name, rate))
                append(measure(source, rate)).append('\n')
            }
        }

        append("\nA source that opens with a level of zero is routed but silent — ")
        append("usually the MCU holding the microphone muted outside a call.\n")
    }

    /**
     * Opens one source and reports the loudest thing it hears in a short window.
     *
     * Root mean square rather than a peak: a single spurious sample would read as
     * a working microphone, where the average over a window will not.
     */
    private fun measure(source: Int, sampleRate: Int): String {
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        }.getOrDefault(AudioRecord.ERROR)

        if (minBuffer <= 0) return "unsupported"

        var record: AudioRecord? = null
        return try {
            record = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) return "uninitialised"

            record.startRecording()
            val buffer = ShortArray(minBuffer)
            var loudest = 0.0
            // Several reads: the first often returns before the input has settled,
            // and reporting that would look like silence on a working source.
            repeat(READS) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i]
                    loudest = maxOf(loudest, sqrt(sum / read))
                }
            }
            record.stop()

            val level = (loudest / Short.MAX_VALUE * 100).toInt()
            if (level == 0) "opened      silent" else "opened      $level%"
        } catch (e: SecurityException) {
            "no permission"
        } catch (e: Exception) {
            e.javaClass.simpleName
        } finally {
            runCatching { record?.release() }
        }
    }

    private const val READS = 12
}
