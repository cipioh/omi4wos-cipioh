package com.omi4wos.wear.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.omi4wos.shared.Constants
import java.io.ByteArrayOutputStream

/**
 * Opus encoder for compressing PCM16 speech audio before sending to the phone.
 * Uses Android's MediaCodec API which supports Opus encoding on API 29+.
 *
 * This outputs raw Opus frames prefixed by a 4-byte little-endian length,
 * exactly matching the Limitless Pendant .bin format!
 */
class OpusEncoder {

    companion object {
        private const val TAG = "OpusEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val TIMEOUT_US = 10_000L // 10ms timeout for codec operations
    }

    private var codec: MediaCodec? = null
    private var isInitialized = false
    private var useFallbackPcm = false

    init {
        try {
            val format = MediaFormat.createAudioFormat(MIME_TYPE, Constants.SAMPLE_RATE, 1)
            // Limitless uses around 16kbps for efficient wearable streaming
            format.setInteger(MediaFormat.KEY_BIT_RATE, 16000)
            
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec?.start()

            useFallbackPcm = false
            isInitialized = true
            Log.i(TAG, "Audio encoder: Limitless Opus format (${Constants.SAMPLE_RATE}Hz mono, 16kbps)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init MediaCodec Opus encoder, using PCM fallback", e)
            useFallbackPcm = true
            isInitialized = true
        }
    }

    /**
     * Encode PCM16 samples to Opus (or raw PCM bytes as fallback).
     * @param samples PCM16 audio samples
     * @return Encoded bytes, or null on failure
     */
    fun encode(samples: ShortArray): ByteArray? {
        if (!isInitialized) return null

        if (useFallbackPcm) {
            return encodePcmFallback(samples)
        }

        return try {
            encodeOpus(samples)
        } catch (e: Exception) {
            Log.e(TAG, "Opus encoding failed, using PCM fallback", e)
            encodePcmFallback(samples)
        }
    }

    /**
     * Encode samples using MediaCodec Opus encoder.
     */
    private fun encodeOpus(samples: ShortArray): ByteArray? {
        val mediaCodec = codec ?: return null
        val outputStream = ByteArrayOutputStream()

        // Convert shorts to bytes (little-endian PCM16)
        val inputBytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            inputBytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            inputBytes[i * 2 + 1] = (samples[i].toInt() shr 8 and 0xFF).toByte()
        }

        // Feed input to encoder
        var inputOffset = 0
        while (inputOffset < inputBytes.size) {
            val inputBufferIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex) ?: continue
                val chunkSize = minOf(inputBuffer.remaining(), inputBytes.size - inputOffset)
                inputBuffer.put(inputBytes, inputOffset, chunkSize)
                mediaCodec.queueInputBuffer(
                    inputBufferIndex, 0, chunkSize,
                    System.nanoTime() / 1000, 0
                )
                inputOffset += chunkSize
            }

            // Drain output
            drainEncoder(mediaCodec, outputStream)
        }

        // Final drain
        drainEncoder(mediaCodec, outputStream)

        val result = outputStream.toByteArray()
        return if (result.isNotEmpty()) result else null
    }

    /**
     * Drain available output buffers from the encoder.
     * Formats the output stream identically to Limitless .bin sequence.
     */
    private fun drainEncoder(mediaCodec: MediaCodec, outputStream: ByteArrayOutputStream) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputBufferIndex >= 0) {
                val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex) ?: break
                
                // Skip Codec Config (OpusHead). Limitless .bin does not use containers.
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                    continue
                }

                if (bufferInfo.size > 0) {
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(chunk)
                    
                    // Prefix: 4-byte little-endian uint32 frame length (Limitless .bin format)
                    val len = chunk.size
                    outputStream.write(len and 0xFF)
                    outputStream.write((len shr 8) and 0xFF)
                    outputStream.write((len shr 16) and 0xFF)
                    outputStream.write((len shr 24) and 0xFF)
                    
                    // Body: Raw Opus compressed frame
                    outputStream.write(chunk)
                }
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
            } else {
                break
            }
        }
    }

    /**
     * Fallback: convert PCM16 shorts to raw bytes.
     * Less efficient but guaranteed to work on all devices.
     */
    private fun encodePcmFallback(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            bytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (samples[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Release encoder resources.
     */
    fun release() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Opus encoder", e)
        }
        codec = null
        isInitialized = false
        Log.i(TAG, "Opus encoder released")
    }
}
