package com.evensocom.psyopvisr.inference

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Streaming Arabic speech -> English text for G2 mic PCM.
 *
 * Uses:
 *  - Vosk Arabic ASR model (downloaded on first use)
 *  - ML Kit on-device translation (Arabic -> English)
 */
class ArabicSpeechTranslationEngine(private val context: Context) {

    companion object {
        private const val TAG = "ArabicSpeechTranslate"
        private const val VOSK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
        private const val VOSK_MODEL_DIR = "vosk-model-ar-mgb2-0.4"
        private const val MODEL_READY_SENTINEL = "am/final.mdl"
    }

    @Volatile private var initialized = false
    @Volatile private var modelLoading = false

    private var model: Model? = null
    private var recognizer16k: Recognizer? = null
    private var recognizer8k: Recognizer? = null
    private var translator: Translator? = null

    suspend fun ensureInitialized() {
        if (initialized || modelLoading) return
        modelLoading = true
        try {
            withContext(Dispatchers.IO) {
                val modelDir = ensureArabicModelOnDisk()
                if (model == null) {
                    model = Model(modelDir.absolutePath)
                }
                if (recognizer16k == null) {
                    recognizer16k = Recognizer(model, 16_000.0f).apply { setWords(false) }
                }
                if (recognizer8k == null) {
                    recognizer8k = Recognizer(model, 8_000.0f).apply { setWords(false) }
                }
            }

            if (translator == null) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ARABIC)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
                translator = Translation.getClient(options)
            }

            translator?.downloadModelIfNeeded(
                DownloadConditions.Builder().build()
            )?.await()

            initialized = true
            Log.i(TAG, "Arabic translation engine initialized")
        } finally {
            modelLoading = false
        }
    }

    suspend fun processPcmChunk(pcmData: ByteBuffer): String? {
        if (!initialized) return null
        val r16 = recognizer16k ?: return null
        val r8 = recognizer8k ?: return null

        val chunk = ByteArray(pcmData.remaining())
        pcmData.duplicate().get(chunk)

        if (chunk.isEmpty()) return null

        val candidate16 = runRecognizer(r16, chunk)
        val candidate8 = runRecognizer(r8, chunk)

        val arText = when {
            candidate16.first && candidate16.second.isNotBlank() -> candidate16.second
            candidate8.first && candidate8.second.isNotBlank() -> candidate8.second
            candidate16.second.length >= candidate8.second.length -> candidate16.second
            else -> candidate8.second
        }
        if (arText.isBlank()) return null

        val en = translator?.translate(arText.take(240))?.await()?.trim().orEmpty()
        if (en.isBlank()) return null
        return en
    }

    fun resetSession() {
        val m = model ?: return
        recognizer16k?.close()
        recognizer8k?.close()
        recognizer16k = Recognizer(m, 16_000.0f).apply { setWords(false) }
        recognizer8k = Recognizer(m, 8_000.0f).apply { setWords(false) }
    }

    fun close() {
        translator?.close()
        translator = null
        recognizer16k?.close()
        recognizer8k?.close()
        recognizer16k = null
        recognizer8k = null
        model?.close()
        model = null
        initialized = false
        modelLoading = false
    }

    private suspend fun runRecognizer(recognizer: Recognizer, chunk: ByteArray): Pair<Boolean, String> {
        val hasFinal = withContext(Dispatchers.Default) {
            recognizer.acceptWaveForm(chunk, chunk.size)
        }
        val json = withContext(Dispatchers.Default) {
            if (hasFinal) recognizer.result else recognizer.partialResult
        }
        val arText = extractJsonText(json, if (hasFinal) "text" else "partial")
        if (!hasFinal && arText.length < 8) {
            return false to ""
        }
        return hasFinal to arText
    }

    private fun extractJsonText(json: String, key: String): String {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1).orEmpty().trim()
    }

    private fun ensureArabicModelOnDisk(): File {
        val modelsRoot = File(context.filesDir, "speech_models").apply { mkdirs() }
        val modelDir = File(modelsRoot, VOSK_MODEL_DIR)
        val sentinel = File(modelDir, MODEL_READY_SENTINEL)

        if (sentinel.exists()) {
            return modelDir
        }

        val zipFile = File(modelsRoot, "$VOSK_MODEL_DIR.zip.part")
        downloadFile(VOSK_MODEL_URL, zipFile)
        unzip(zipFile, modelsRoot)
        zipFile.delete()

        if (!sentinel.exists()) {
            throw IllegalStateException("Vosk Arabic model missing after unzip: ${sentinel.absolutePath}")
        }
        return modelDir
    }

    private fun downloadFile(url: String, dest: File) {
        Log.i(TAG, "Downloading Vosk Arabic model: $url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            requestMethod = "GET"
            doInput = true
            connect()
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("Model download failed HTTP ${conn.responseCode}")
        }
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()
    }

    private fun unzip(zipFile: File, destinationDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destinationDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        copyStream(zis, fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { error ->
            if (cont.isActive) cont.resumeWithException(error)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }
}
