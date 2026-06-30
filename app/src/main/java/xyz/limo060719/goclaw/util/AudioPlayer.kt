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

    fun play(bytes: ByteArray) {
        stop()
        runCatching {
            val file = File.createTempFile("tts_", ".audio", context.cacheDir)
            file.writeBytes(bytes)
            player = MediaPlayer().apply {
                setOnCompletionListener {
                    runCatching { it.release() }
                    file.delete()
                    if (player === it) player = null
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    file.delete()
                    if (player === mp) player = null
                    true
                }
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        }.onFailure { stop() }
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
