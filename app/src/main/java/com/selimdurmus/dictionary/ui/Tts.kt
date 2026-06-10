package com.selimdurmus.dictionary.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Best-effort offline pronunciation via Android's system TextToSpeech. Init is async; [speak] is a
 * no-op until the engine is ready, and quietly skips a word whose language voice isn't installed
 * (so a missing Turkish voice just produces silence rather than an error).
 */
class TtsController(context: Context) {
    @Volatile private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    fun speak(text: String, lang: String) {
        if (!ready || text.isBlank()) return
        val result = tts.setLanguage(Locale(lang))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dict-tts")
    }

    fun shutdown() = tts.shutdown()
}

/** Remember a [TtsController] tied to the composition; shuts the engine down on dispose. */
@Composable
fun rememberTts(): TtsController {
    val context = LocalContext.current
    val controller = remember { TtsController(context) }
    DisposableEffect(Unit) { onDispose { controller.shutdown() } }
    return controller
}
