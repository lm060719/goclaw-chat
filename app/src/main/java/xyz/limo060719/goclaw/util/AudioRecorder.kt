package xyz.limo060719.goclaw.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records microphone audio to an MP4/AAC (`.m4a`) file via [MediaRecorder].
 * One active recording at a time. Not injected — instantiated directly with a Context.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** Begins recording. Returns false if it could not start (e.g. no mic permission). */
    fun start(): Boolean {
        if (recorder != null) return false
        return runCatching {
            val file = File.createTempFile("voice_", ".m4a", context.cacheDir)
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = file
            true
        }.getOrElse {
            releaseQuietly()
            false
        }
    }

    /** Stops recording and returns the recorded file, or null on failure. */
    fun stop(): File? {
        val mr = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        return runCatching {
            mr.stop()
            mr.release()
            file
        }.getOrElse {
            // stop() throws if called too soon after start(); discard the (unusable) file.
            runCatching { mr.release() }
            file?.delete()
            null
        }
    }

    /** Cancels recording and discards the file. */
    fun cancel() {
        val mr = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (mr != null) {
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        file?.delete()
    }

    private fun releaseQuietly() {
        runCatching { recorder?.release() }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
