package com.example.mangareader.tts

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class MangaTTSManager(private val context: Context) {

    companion object {
        private const val TAG = "MangaTTSManager"
        private const val MS_PER_WORD = 380L
        private const val MIN_PANEL_DURATION = 2500L
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRate = 0.9f
    private var currentUtteranceId: String? = null
    private var onSpeakComplete: (() -> Unit)? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init { initTTS() }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.ENGLISH
                tts?.setSpeechRate(speechRate)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == currentUtteranceId) onSpeakComplete?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { onSpeakComplete?.invoke() }
                })
            }
        }
    }

    /**
     * Extracts text from the bitmap and sorts it for Manga:
     * - Speech bubbles (blocks) are read Right-to-Left.
     * - Lines within a bubble are read Top-to-Bottom.
     * - Words within a line are read Left-to-Right (standard).
     */
    suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                // Sort blocks by their horizontal center (Right-to-Left)
                val sortedBlocks = result.textBlocks.sortedWith(
                    compareByDescending<Text.TextBlock> { block -> 
                        val rect = block.boundingBox
                        if (rect != null) (rect.left + rect.right) / 2 else 0 
                    }.thenBy { it.boundingBox?.top ?: 0 }
                )
                
                val combinedText = sortedBlocks.joinToString(" ") { block ->
                    // Lines within the block should be read Top-to-Bottom
                    block.lines.sortedBy { it.boundingBox?.top ?: 0 }
                        .joinToString(" ") { it.text }
                }
                
                cont.resume(cleanMangaText(combinedText))
            }
            .addOnFailureListener { Log.w(TAG, "OCR failed", it); cont.resume("") }
    }

    fun speak(text: String, onComplete: () -> Unit) {
        onSpeakComplete = onComplete
        if (!ttsReady || tts == null) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onComplete, 2000L); return
        }
        if (text.isBlank()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onComplete, MIN_PANEL_DURATION); return
        }
        stop()
        currentUtteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId)
    }

    fun estimateDuration(text: String): Long {
        if (text.isBlank()) return MIN_PANEL_DURATION
        val wordCount = text.trim().split(Regex("\\s+")).size
        return (wordCount * MS_PER_WORD / speechRate).toLong().coerceAtLeast(MIN_PANEL_DURATION)
    }

    fun stop() { tts?.stop() }
    fun setSpeechRate(rate: Float) { speechRate = rate.coerceIn(0.5f, 2.0f); tts?.setSpeechRate(speechRate) }
    fun setLanguage(locale: Locale) { tts?.language = locale }

    fun release() { tts?.stop(); tts?.shutdown(); tts = null; recognizer.close() }

    private fun cleanMangaText(raw: String): String =
        raw.lines()
            .filter { it.trim().isNotBlank() && it.trim().length > 1 && it.trim().matches(Regex(".*[a-zA-Z].*")) }
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s{2,}"), " ").trim()
}
