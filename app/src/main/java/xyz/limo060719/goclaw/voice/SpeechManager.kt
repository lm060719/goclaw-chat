package xyz.limo060719.goclaw.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VoiceEvent {
    data object Ready : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Error(val message: String) : VoiceEvent
    data object End : VoiceEvent
}

@Singleton
class SpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Collect on the main thread (viewModelScope). Cancelling the flow stops recognition. */
    fun listen(localeTag: String = Locale.getDefault().toLanguageTag()): Flow<VoiceEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(VoiceEvent.Ready) }
            override fun onPartialResults(partial: Bundle?) {
                partial?.firstResult()?.let { trySend(VoiceEvent.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                results?.firstResult()?.let { trySend(VoiceEvent.Final(it)) }
                trySend(VoiceEvent.End); close()
            }
            override fun onError(error: Int) {
                trySend(VoiceEvent.Error(errorText(error))); close()
            }
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        }
        recognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
        awaitClose {
            recognizer.stopListening()
            recognizer.destroy()
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        ensureTts { tts ->
            applyLanguage(tts, text)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "goclaw-${System.currentTimeMillis()}")
        }
    }

    /** Picks the TTS language from the text so Chinese replies aren't read with an English voice. */
    private fun applyLanguage(tts: TextToSpeech, text: String) {
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
        val candidates = if (hasChinese) {
            listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale("zh", "CN"))
        } else {
            listOf(Locale.getDefault(), Locale.ENGLISH)
        }
        for (loc in candidates) {
            val r = tts.setLanguage(loc)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return
        }
    }

    fun stopSpeaking() { tts?.stop() }

    fun shutdown() { tts?.shutdown(); tts = null }

    private fun ensureTts(onReady: (TextToSpeech) -> Unit) {
        val existing = tts
        if (existing != null) { onReady(existing); return }
        tts = TextToSpeech(context) { status ->
            // Language is chosen per-utterance in applyLanguage() based on the text.
            if (status == TextToSpeech.SUCCESS) tts?.let(onReady)
        }
    }

    private fun Bundle.firstResult(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音输入"
        else -> "语音识别错误($code)"
    }
}
