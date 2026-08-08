package com.openlauncher.app.util

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
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

    /**
     * One rate rather than four.
     *
     * Twenty consecutive opens of the audio HAL is what took the launcher down:
     * a head unit's driver is not built for that, and a failed open can leave
     * the input in a state the next one trips over. 16 kHz is the rate voice
     * input is universally wired for, so it costs nothing to drop the rest.
     */
    private const val SAMPLE_RATE = 16000

    /** Lets the HAL release the input before the next source claims it. */
    private const val SETTLE_MS = 250L

    private const val READS = 12

    /**
     * Runs the probe, writing the report as it is produced.
     *
     * Written progressively rather than returned whole because the previous
     * version crashed partway and left nothing at all — losing the single fact
     * worth having, which is *which source* killed it. Now the file ends at
     * whatever was being attempted, and that names the culprit.
     */
    fun run(context: Context, sink: File): String {
        val out = StringBuilder()
        fun emit(line: String) {
            out.append(line)
            runCatching { sink.writeText(out.toString()) }
        }

        emit("Microphone probe\n")
        emit("=".repeat(52) + "\n")

        val audio = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
        // Reported first because it explains a silent recording on its own, and
        // is the one cause that can be undone rather than worked around.
        emit("\nmicrophone muted : ${runCatching { audio?.isMicrophoneMute }.getOrNull()}\n")
        emit("mode             : ${runCatching { audio?.mode }.getOrNull()}\n")
        emit("wired headset    : ${runCatching {
            @Suppress("DEPRECATION") audio?.isWiredHeadsetOn
        }.getOrNull()}\n")
        emit("bluetooth sco    : ${runCatching {
            @Suppress("DEPRECATION") audio?.isBluetoothScoOn
        }.getOrNull()}\n")

        emit("\nsource                rate    state       level\n")
        for ((name, source) in SOURCES) {
            emit("%-20s %6d  ".format(name, SAMPLE_RATE))
            // Throwable rather than Exception: a bad audio HAL surfaces as an
            // Error, and that is precisely the case worth surviving here.
            val result = try {
                measure(source, SAMPLE_RATE)
            } catch (t: Throwable) {
                "FAILED ${t.javaClass.simpleName}"
            }
            emit("$result\n")
            runCatching { Thread.sleep(SETTLE_MS) }
        }

        emit("\nA source that opens with a level of zero is routed but silent — ")
        emit("usually the MCU holding the microphone muted outside a call.\n")
        return out.toString()
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
            runCatching { record.stop() }

            val level = (loudest / Short.MAX_VALUE * 100).toInt()
            if (level == 0) "opened      silent" else "opened      $level%"
        } catch (e: SecurityException) {
            "no permission"
        } finally {
            runCatching { record?.release() }
        }
    }
}
