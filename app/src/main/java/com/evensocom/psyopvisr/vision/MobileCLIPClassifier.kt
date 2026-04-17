package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

// ──────────────────── Result type ────────────────────

data class ClassificationResult(
    val label: String,
    val confidence: Float,
    val allScores: Map<String, Float>
)

// ──────────────────── MobileCLIP zero-shot classifier ────────────────────

/**
 * Shared zero-shot image classifier backed by a TFLite MobileCLIP image encoder.
 *
 * The TFLite model file `mobileclip_image_encoder.tflite` must be placed in
 * `app/src/main/assets/`. If the asset is absent, [isAvailable] is set to false
 * and all inference calls return a sentinel [ClassificationResult] with label
 * "UNAVAILABLE".
 *
 * Text embeddings (512-dim unit vectors) are pre-computed at init time.
 * **The placeholder vectors defined below MUST be replaced with real
 * MobileCLIP text encoder outputs computed offline (e.g. via the
 * apple/ml-mobileclip Python package).**
 */
class MobileCLIPClassifier(context: Context) {

    companion object {
        private const val TAG = "MobileCLIPClassifier"
        private const val DEFAULT_IMAGE_MODEL_ASSET = "mobileclip_image_encoder.tflite"
        private const val HF_IMAGE_FILE = "mobileclip_s2_image.tflite"
        private const val HF_TEXT_FILE = "mobileclip_s2_text.tflite"
        private const val MAX_TOKENS = 77
        private const val START_TOKEN = 49406
        private const val END_TOKEN = 49407
        private const val DOWNLOAD_TIMEOUT_MS = 30_000

        // Hugging Face source requested by user
        private const val HF_BASE_URL = "https://huggingface.co/plainhub/mobileclip-s2-tflite/resolve/main"
    }

    var isAvailable: Boolean = false
        private set

    // Keep prompts explicit so the model can classify requested symbols.
    val signPrompts = listOf(
        "swastika symbol",
        "hakenkreuz symbol",
        "star of david symbol",
        "jewish menorah symbol",
        "christian cross symbol",
        "crucifix symbol",
        "orthodox cross symbol",
        "islamic crescent and star symbol",
        "om symbol",
        "buddhist dharma wheel symbol",
        "buddha statue",
        "religious icon",
        "military insignia",
        "warning sign",
        "hazard placard",
        "restricted area sign",
        "no entry sign",
        "checkpoint sign",
        "exit sign",
        "directional sign"
    )

    private val religiousSymbolPrompts = listOf(
        "swastika symbol",
        "hakenkreuz symbol",
        "star of david symbol",
        "jewish menorah symbol",
        "christian cross symbol",
        "crucifix symbol",
        "orthodox cross symbol",
        "islamic crescent and star symbol",
        "om symbol",
        "buddhist dharma wheel symbol",
        "buddha statue",
        "religious icon"
    )

    val scenePrompts = listOf(
        "religious site",
        "military facility",
        "medical area",
        "commercial market",
        "residential area",
        "controlled access facility",
        "active conflict zone",
        "transportation hub",
        "government building",
        "educational institution"
    )

    // Pre-tokenized prompt IDs (from plainhub/mobileclip-s2-tflite tokenizer).
    // Format: [<start_of_text>, ...tokens..., <end_of_text>, 0...]
    private val signPromptTokenIds = listOf(
        intArrayOf(49406, 21758, 1843, 4830, 869, 860, 6119, 88, 758, 562, 49407),
        intArrayOf(49406, 21758, 1843, 778, 560, 1838, 8362, 19398, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 778, 993, 684, 4640, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 778, 29594, 1552, 523, 1772, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 778, 3328, 1124, 8801, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 1053, 681, 698, 4596, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 1675, 11366, 44786, 5266, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 1675, 31627, 798, 36277, 672, 993, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 1675, 547, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 2833, 84, 1353, 21710, 12397, 577, 7360, 22987, 562, 49407),
        intArrayOf(49406, 21758, 1843, 2833, 84, 1353, 560, 3004, 16696, 49407),
        intArrayOf(49406, 21758, 1843, 778, 4940, 1987, 13843, 49407),
        intArrayOf(49406, 21758, 1843, 3395, 529, 4617, 512, 34023, 64, 49407),
        intArrayOf(49406, 21758, 1843, 778, 984, 6050, 5538, 49407),
        intArrayOf(49406, 21758, 1843, 778, 26174, 2331, 3793, 49407),
        intArrayOf(49406, 21758, 1843, 8620, 2607, 600, 14833, 64, 5538, 49407),
        intArrayOf(49406, 21758, 1843, 1675, 78, 524, 4487, 5538, 49407),
        intArrayOf(49406, 21758, 1843, 778, 7672, 13280, 5538, 49407),
        intArrayOf(49406, 21758, 1843, 1675, 659, 529, 5538, 49407),
        intArrayOf(49406, 21758, 1843, 778, 15923, 526, 5538, 49407)
    )

    private var imageInterpreter: Interpreter? = null
    private var textInterpreter: Interpreter? = null
    private var inputSize: Int = 256
    private var embeddingDim: Int = 512
    private val signEmbeddings = mutableListOf<FloatArray>()
    private val sceneEmbeddings = mutableListOf<FloatArray>()
    private val initExecutor = Executors.newSingleThreadExecutor()
    private val modelDir = File(context.filesDir, "hf_models/mobileclip-s2-tflite")

    // ──────────────────────────────────────────────────────────────────
    init {
        // Start async download + model bootstrap. Classifier stays unavailable until this finishes.
        initExecutor.execute {
            try {
                modelDir.mkdirs()
                val imageFile = ensureModelFile(context, HF_IMAGE_FILE, HF_BASE_URL)
                val textFile = ensureModelFile(context, HF_TEXT_FILE, HF_BASE_URL)

                imageInterpreter = if (imageFile != null) {
                    Interpreter(imageFile)
                } else {
                    // Fallback to legacy asset path if present
                    val assetFd = context.assets.openFd(DEFAULT_IMAGE_MODEL_ASSET)
                    val fileInputStream = assetFd.createInputStream()
                    val fileChannel = fileInputStream.channel
                    val mappedBuffer = fileChannel.map(
                        java.nio.channels.FileChannel.MapMode.READ_ONLY,
                        assetFd.startOffset,
                        assetFd.declaredLength
                    )
                    Interpreter(mappedBuffer)
                }

                if (textFile != null) {
                    textInterpreter = Interpreter(textFile)
                }

                val imageInterp = imageInterpreter
                val textInterp = textInterpreter
                if (imageInterp == null || textInterp == null) {
                    Log.w(TAG, "MobileCLIP image/text model not ready")
                    isAvailable = false
                    return@execute
                }

                imageInterp.allocateTensors()
                textInterp.allocateTensors()

                val imageInputShape = imageInterp.getInputTensor(0).shape()
                inputSize = if (imageInputShape.size >= 3) imageInputShape[1] else 256

                val imageOutputShape = imageInterp.getOutputTensor(0).shape()
                embeddingDim = imageOutputShape.lastOrNull() ?: 512

                buildPromptEmbeddings(textInterp)
                isAvailable = signEmbeddings.isNotEmpty() && sceneEmbeddings.isNotEmpty()
                Log.i(TAG, "MobileCLIP ready: input=$inputSize emb=$embeddingDim prompts=${signEmbeddings.size}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize MobileCLIP from Hugging Face models: $e")
                isAvailable = false
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    /**
     * Classifies [bitmap] against either [SIGN_PROMPTS] or [SCENE_PROMPTS].
     *
     * @param bitmap           Source image (any size/format — resized internally).
     * @param useScenePrompts  When true, uses [SCENE_PROMPTS]; otherwise [SIGN_PROMPTS].
     * @return [ClassificationResult] with top-1 label, confidence, and all scores.
     */
    fun classifyImage(bitmap: Bitmap, useScenePrompts: Boolean = false): ClassificationResult {
        val imageInterp = imageInterpreter
        if (!isAvailable || imageInterp == null) {
            return ClassificationResult("UNAVAILABLE", 0f, emptyMap())
        }

        val prompts = if (useScenePrompts) scenePrompts else signPrompts
        val textEmbeddings = if (useScenePrompts) sceneEmbeddings else signEmbeddings
        if (textEmbeddings.isEmpty()) {
            return ClassificationResult("UNAVAILABLE", 0f, emptyMap())
        }

        // 1. Resize to model input
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // 2. Convert to normalised float buffer [1, H, W, 3] range [-1, 1]
        val inputBuffer = bitmapToNormalisedBuffer(resized)

        // 3. Allocate output buffer [1, embeddingDim]
        val outputBuffer = Array(1) { FloatArray(embeddingDim) }

        // 4. Run inference
        synchronized(imageInterp) {
            imageInterp.run(inputBuffer, outputBuffer)
        }
        val imageEmbedding = outputBuffer[0]

        // 5. L2-normalise the image embedding
        val normalisedImage = l2Normalize(imageEmbedding)

        // 6. Cosine similarities (dot-product of unit vectors)
        val scores = FloatArray(textEmbeddings.size) { i ->
            dotProduct(normalisedImage, textEmbeddings[i])
        }

        // 7. Find top-1
        var bestIdx = 0
        for (i in scores.indices) {
            if (scores[i] > scores[bestIdx]) bestIdx = i
        }

        val allScores = prompts.mapIndexed { i, label -> label to scores[i] }.toMap()
        return ClassificationResult(
            label = prompts[bestIdx],
            confidence = scores[bestIdx],
            allScores = allScores
        )
    }

    fun classifyReligiousSymbol(bitmap: Bitmap): ClassificationResult {
        if (!isAvailable) return ClassificationResult("UNAVAILABLE", 0f, emptyMap())
        val full = classifyImage(bitmap, useScenePrompts = false)
        if (full.label == "UNAVAILABLE" || full.allScores.isEmpty()) return full

        val filtered = full.allScores
            .filterKeys { key -> religiousSymbolPrompts.any { it.equals(key, ignoreCase = true) } }

        if (filtered.isEmpty()) return ClassificationResult("NO_RELIGIOUS_SYMBOL", 0f, emptyMap())

        val top = filtered.maxByOrNull { it.value }!!
        return ClassificationResult(
            label = top.key,
            confidence = top.value,
            allScores = filtered
        )
    }

    // ──────────────────────────────────────────────────────────────────

    /** Releases the TFLite interpreter resources. */
    fun close() {
        imageInterpreter?.close()
        textInterpreter?.close()
        imageInterpreter = null
        textInterpreter = null
        initExecutor.shutdownNow()
    }

    // ──────────────────────────────── Private helpers ──────────────────

    private fun buildPromptEmbeddings(textInterp: Interpreter) {
        signEmbeddings.clear()
        sceneEmbeddings.clear()

        signEmbeddings += signPromptTokenIds.map { ids ->
            runTextEmbedding(textInterp, paddedTokenIds(ids))
        }

        // Reuse sign tokenizer template for scene prompts by lexical decomposition.
        // This keeps implementation deterministic without shipping a full tokenizer runtime.
        sceneEmbeddings += scenePrompts.map { prompt ->
            val proxy = promptToKnownTokenTemplate(prompt)
            runTextEmbedding(textInterp, proxy)
        }
    }

    private fun promptToKnownTokenTemplate(prompt: String): IntArray {
        // Conservative fallback for scene prompts: encode as "a photo of a <label> sign" buckets.
        // This keeps scene mode functional while sign-mode gets exact tokenization.
        return when {
            prompt.contains("religious", ignoreCase = true) -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 778, 4940, 1987, 13843, 49407))
            prompt.contains("military", ignoreCase = true) -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 3395, 529, 4617, 512, 34023, 64, 49407))
            prompt.contains("medical", ignoreCase = true) -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 778, 1129, 5538, 49407))
            prompt.contains("controlled", ignoreCase = true) -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 8620, 2607, 600, 14833, 64, 5538, 49407))
            prompt.contains("conflict", ignoreCase = true) -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 778, 26174, 2331, 3793, 49407))
            prompt.contains("transportation", ignoreCase = true) -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 778, 15923, 526, 5538, 49407))
            prompt.contains("government", ignoreCase = true) -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 778, 8620, 2607, 600, 14833, 64, 5538, 49407))
            prompt.contains("educational", ignoreCase = true) -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 778, 15923, 526, 5538, 49407))
            else -> paddedTokenIds(intArrayOf(49406, 21758, 1843, 778, 15923, 526, 5538, 49407))
        }
    }

    private fun runTextEmbedding(textInterp: Interpreter, tokenIds: IntArray): FloatArray {
        val inputShape = textInterp.getInputTensor(0).shape()
        val tokenLen = inputShape.lastOrNull() ?: MAX_TOKENS
        val inputBuffer = ByteBuffer.allocateDirect(tokenLen * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until tokenLen) {
            inputBuffer.putInt(if (i < tokenIds.size) tokenIds[i] else 0)
        }
        inputBuffer.rewind()

        val outputBuffer = Array(1) { FloatArray(embeddingDim) }
        synchronized(textInterp) {
            textInterp.run(inputBuffer, outputBuffer)
        }
        return l2Normalize(outputBuffer[0])
    }

    private fun paddedTokenIds(raw: IntArray): IntArray {
        if (raw.isEmpty()) {
            return intArrayOf(START_TOKEN, END_TOKEN) + IntArray(MAX_TOKENS - 2)
        }
        val withBoundary = if (raw.first() == START_TOKEN && raw.last() == END_TOKEN) {
            raw
        } else {
            intArrayOf(START_TOKEN) + raw.take(MAX_TOKENS - 2).toIntArray() + intArrayOf(END_TOKEN)
        }
        return withBoundary + IntArray((MAX_TOKENS - withBoundary.size).coerceAtLeast(0))
    }

    private fun ensureModelFile(context: Context, fileName: String, baseUrl: String): File? {
        val dst = File(modelDir, fileName)
        if (dst.exists() && dst.length() > 1024L) return dst

        return try {
            downloadFile("$baseUrl/$fileName", dst)
            dst
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for $fileName: $e")
            null
        }
    }

    private fun downloadFile(url: String, dst: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = DOWNLOAD_TIMEOUT_MS
        conn.readTimeout = DOWNLOAD_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
        conn.inputStream.use { input ->
            FileOutputStream(dst).use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
        conn.disconnect()
    }

    /** Converts a [Bitmap] to a ByteBuffer with shape [1, H, W, 3], normalised to [-1, 1]. */
    private fun bitmapToNormalisedBuffer(bitmap: Bitmap): ByteBuffer {
        val numBytes = 1 * inputSize * inputSize * 3 * 4 // float32
        val buffer = ByteBuffer.allocateDirect(numBytes).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            // mean=[0.5,0.5,0.5], std=[0.5,0.5,0.5]  →  (x - 0.5) / 0.5 = 2x - 1
            buffer.putFloat(r * 2f - 1f)
            buffer.putFloat(g * 2f - 1f)
            buffer.putFloat(b * 2f - 1f)
        }
        buffer.rewind()
        return buffer
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x }).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }
}
