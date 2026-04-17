package com.evensocom.psyopvisr.inference

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes G2 mic LC3 packets to 16 kHz mono S16LE PCM.
 *
 * Strategy:
 *  1) Native liblc3 decoder via JNI (`liblc3codec.so`) for real 205B G2 packets.
 *  2) MediaCodec "audio/lc3" fallback if native library is unavailable.
 */
class Lc3Decoder {

    companion object {
        private const val TAG = "Lc3Decoder"
        private const val G2_PACKET_BYTES = 205
        private const val G2_LC3_FRAME_BYTES = 40
        private const val G2_LC3_PAYLOAD_BYTES = 200 // 5 frames x 40B

        private fun tryLoadNative(): Boolean = try {
            System.loadLibrary("lc3codec")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Native liblc3codec unavailable: $e")
            false
        }
    }

    private val nativeAvailable: Boolean
    private var nativeHandle: Long = 0L
    private var codec: MediaCodec? = null
    private var codecReady = false
    private var ptsUs = 0L
    private var decodeFailures = 0
    private var nativeDecodedPackets = 0L
    private var nativeEmptyPackets = 0L

    init {
        nativeAvailable = tryLoadNative()
        if (nativeAvailable) {
            nativeHandle = nativeCreateDecoder()
            if (nativeHandle == 0L) {
                Log.e(TAG, "Native LC3 decoder failed to initialize; falling back to MediaCodec")
                initMediaCodec()
            } else {
                Log.i(TAG, "Native LC3 decoder initialized")
            }
        } else {
            initMediaCodec()
        }
    }

    /**
     * Decode one raw G2 mic packet from 6402 into PCM bytes.
     */
    fun decode(lc3Payload: ByteArray): ByteBuffer {
        val pcmBytes = if (nativeHandle != 0L) {
            try {
                nativeDecodePacket(nativeHandle, lc3Payload)
            } catch (e: Throwable) {
                Log.w(TAG, "Native LC3 decode failed: $e")
                byteArrayOf()
            }
        } else {
            decodeViaMediaCodec(lc3Payload)
        }

        if (nativeHandle != 0L) {
            if (pcmBytes.isNotEmpty()) {
                nativeDecodedPackets++
                if (nativeDecodedPackets == 1L || nativeDecodedPackets % 200L == 0L) {
                    Log.i(
                        TAG,
                        "Native LC3 decode ok packets=$nativeDecodedPackets outBytes=${pcmBytes.size}"
                    )
                }
            } else {
                nativeEmptyPackets++
                if (nativeEmptyPackets <= 5L || nativeEmptyPackets % 200L == 0L) {
                    Log.w(TAG, "Native LC3 decode returned empty (#$nativeEmptyPackets payload=${lc3Payload.size}B)")
                }
            }
        }

        val pcmBuffer = ByteBuffer.allocateDirect(pcmBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        pcmBuffer.put(pcmBytes)
        pcmBuffer.flip()
        return pcmBuffer
    }

    fun reset() {
        if (nativeHandle != 0L) {
            try {
                nativeResetDecoder(nativeHandle)
            } catch (e: Throwable) {
                Log.w(TAG, "Native decoder reset failed: $e")
            }
        }
    }

    fun close() {
        if (nativeHandle != 0L) {
            try {
                nativeReleaseDecoder(nativeHandle)
            } catch (e: Throwable) {
                Log.w(TAG, "Native decoder release failed: $e")
            } finally {
                nativeHandle = 0L
            }
        }
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
        codecReady = false
    }

    private fun initMediaCodec() {
        try {
            val c = MediaCodec.createDecoderByType("audio/lc3")
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, "audio/lc3")
                setInteger(MediaFormat.KEY_SAMPLE_RATE, 16_000)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            }
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            codecReady = true
            Log.i(TAG, "MediaCodec audio/lc3 decoder initialized")
        } catch (e: Throwable) {
            codec = null
            codecReady = false
            Log.w(TAG, "MediaCodec audio/lc3 unavailable: $e")
        }
    }

    private fun decodeViaMediaCodec(payload: ByteArray): ByteArray {
        if (!codecReady) return byteArrayOf()
        val c = codec ?: return byteArrayOf()

        // Alternate framing observed in some traces: [0xCC|0xCD, seq, lc3-frame...].
        if (payload.size == G2_PACKET_BYTES &&
            (payload[0].toInt() and 0xFF == 0xCC || payload[0].toInt() and 0xFF == 0xCD)
        ) {
            val one = decodeOneFrame(c, payload.copyOfRange(2, payload.size))
            if (one.isNotEmpty()) return one
        }

        val frames = extractLc3Frames(payload)
        if (frames.isEmpty()) return byteArrayOf()

        val out = ByteArrayOutputStream()
        for (frame in frames) {
            val decoded = decodeOneFrame(c, frame)
            if (decoded.isNotEmpty()) {
                out.write(decoded)
            }
        }
        return out.toByteArray()
    }

    /**
     * G2 payload layout:
     *  - 205B packet: first 200B are 5 LC3 frames, last 5B trailer/counter.
     *  - 200B packet: 5 LC3 frames directly.
     *  - 40B packet: single LC3 frame.
     */
    private fun extractLc3Frames(payload: ByteArray): List<ByteArray> {
        val encoded = when {
            payload.size == G2_PACKET_BYTES -> payload.copyOfRange(0, G2_LC3_PAYLOAD_BYTES)
            payload.size >= G2_LC3_PAYLOAD_BYTES -> payload.copyOfRange(0, G2_LC3_PAYLOAD_BYTES)
            payload.size % G2_LC3_FRAME_BYTES == 0 -> payload
            else -> return emptyList()
        }

        val frames = ArrayList<ByteArray>()
        var off = 0
        while (off + G2_LC3_FRAME_BYTES <= encoded.size) {
            frames.add(encoded.copyOfRange(off, off + G2_LC3_FRAME_BYTES))
            off += G2_LC3_FRAME_BYTES
        }
        return frames
    }

    private fun decodeOneFrame(c: MediaCodec, frame: ByteArray): ByteArray {
        try {
            val inputIndex = c.dequeueInputBuffer(0)
            if (inputIndex < 0) return byteArrayOf()

            val inputBuffer = c.getInputBuffer(inputIndex) ?: return byteArrayOf()
            inputBuffer.clear()
            inputBuffer.put(frame)
            c.queueInputBuffer(inputIndex, 0, frame.size, ptsUs, 0)
            ptsUs += 10_000L

            val info = MediaCodec.BufferInfo()
            val out = ByteArrayOutputStream()
            var outputIndex = c.dequeueOutputBuffer(info, 3_000)
            while (outputIndex >= 0) {
                val outputBuffer = c.getOutputBuffer(outputIndex)
                if (outputBuffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    outputBuffer.get(bytes)
                    out.write(bytes)
                }
                c.releaseOutputBuffer(outputIndex, false)
                outputIndex = c.dequeueOutputBuffer(info, 0)
            }
            return out.toByteArray()
        } catch (e: Throwable) {
            decodeFailures++
            if (decodeFailures <= 5 || decodeFailures % 50 == 0) {
                Log.w(TAG, "MediaCodec LC3 frame decode failed (#$decodeFailures): $e")
            }
            return byteArrayOf()
        }
    }

    private external fun nativeCreateDecoder(): Long
    private external fun nativeReleaseDecoder(handle: Long)
    private external fun nativeResetDecoder(handle: Long)
    private external fun nativeDecodePacket(handle: Long, lc3Payload: ByteArray): ByteArray
}
