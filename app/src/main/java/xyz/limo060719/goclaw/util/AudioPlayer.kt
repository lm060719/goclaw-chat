package xyz.limo060719.goclaw.util

import android.content.Context
import android.media.MediaPlayer
import java.io.File

/**
 * Plays short audio clips (e.g. backend TTS bytes) via [MediaPlayer].
 * One clip at a time — [play] stops any previous playback.
 */
class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    /** Plays the clip. Returns true if playback started, false if the bytes couldn't be played. */
    fun play(bytes: ByteArray): Boolean {
        stop()
        return runCatching {
            val file = File.createTempFile("tts_", ".audio", context.cacheDir)
            file.writeBytes(bytes)
            val mp = MediaPlayer()
            mp.setOnCompletionListener {
                runCatching { it.release() }
                file.delete()
                if (player === it) player = null
            }
            mp.setOnErrorListener { m, _, _ ->
                runCatching { m.release() }
                file.delete()
                if (player === m) player = null
                true
            }
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            player = mp
            true
        }.getOrElse { stop(); false }
    }

    fun stop() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    fun release() = stop()
}
