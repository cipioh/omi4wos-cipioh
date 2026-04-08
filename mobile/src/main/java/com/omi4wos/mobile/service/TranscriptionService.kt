package com.omi4wos.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omi4wos.mobile.data.TranscriptEntity
import com.omi4wos.mobile.data.TranscriptRepository
import com.omi4wos.mobile.omi.OmiApiClient
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.shared.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Service that handles raw Opus audio segments received from the watch, 
 * writing them to Limitless-compatible .bin files, and uploading them straight 
 * to Omi Cloud via v2/sync-local-files.
 */
class TranscriptionService : Service() {

    companion object {
        private const val TAG = "TranscriptionService"
        const val ACTION_TRANSCRIBE = "com.omi4wos.mobile.ACTION_TRANSCRIBE"
        const val EXTRA_SEGMENT_ID = "segment_id"
        const val EXTRA_AUDIO_DATA = "audio_data"
        const val EXTRA_START_TIME = "start_time"
        const val EXTRA_END_TIME = "end_time"
        const val EXTRA_CONFIDENCE = "confidence"

        private val _isTranscribing = MutableStateFlow(false)
        val isTranscribing: StateFlow<Boolean> = _isTranscribing

        private val _transcriptCount = MutableStateFlow(0)
        val transcriptCount: StateFlow<Int> = _transcriptCount
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: TranscriptRepository
    private lateinit var omiClient: OmiApiClient
    private lateinit var omiConfig: OmiConfig

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repository = TranscriptRepository.getInstance(applicationContext)
        omiConfig = OmiConfig(applicationContext)
        omiClient = OmiApiClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRANSCRIBE -> {
                val segmentId = intent.getStringExtra(EXTRA_SEGMENT_ID) ?: ""
                val audioData = intent.getByteArrayExtra(EXTRA_AUDIO_DATA)
                val startTime = intent.getLongExtra(EXTRA_START_TIME, 0)
                val endTime = intent.getLongExtra(EXTRA_END_TIME, 0)
                val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)

                if (audioData != null && audioData.isNotEmpty()) {
                    processSegmentAndUpload(segmentId, audioData, startTime, endTime, confidence)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun processSegmentAndUpload(
        segmentId: String,
        audioData: ByteArray,
        startTime: Long,
        endTime: Long,
        confidence: Float
    ) {
        _isTranscribing.value = true
        Log.i(TAG, "Processing Opus segment $segmentId (${audioData.size} bytes)")

        serviceScope.launch {
            try {
                // Determine Limitless .bin filename matching timestamp
                val timestampSec = startTime / 1000
                val uploadName = "recording_fs320_$timestampSec.bin"

                val cachePath = File(cacheDir, "speech_audio")
                if (!cachePath.exists()) cachePath.mkdirs()
                val binFile = File(cachePath, uploadName)
                
                withContext(Dispatchers.IO) {
                    FileOutputStream(binFile).use { out ->
                        out.write(audioData)
                    }
                }

                val usableToken = omiClient.getValidFirebaseToken(omiConfig)
                if (usableToken.isNullOrBlank()) {
                    Log.w(TAG, "No valid Firebase Token available, cannot upload to Omi Cloud")
                    saveTranscript(segmentId, "[No valid Firebase Token available]", startTime, endTime, confidence)
                    return@launch
                }

                Log.i(TAG, "Uploading directly to Omi Cloud v2/sync-local-files...")
                val result = omiClient.uploadAudioSync(usableToken, binFile, uploadName)

                if (result != null) {
                    saveTranscript(segmentId, "[Uploaded directly to Omi Cloud: $uploadName]", startTime, endTime, confidence)
                    binFile.delete() // Clean up after successful upload
                } else {
                    saveTranscript(segmentId, "[Omi Cloud Sync Failed]", startTime, endTime, confidence)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Cloud sync flow failed for $segmentId", e)
                saveTranscript(segmentId, "[Sync flow failed]", startTime, endTime, confidence)
            } finally {
                _isTranscribing.value = false
            }
        }
    }

    private fun saveTranscript(
        segmentId: String,
        text: String,
        startTime: Long,
        endTime: Long,
        confidence: Float
    ) {
        serviceScope.launch {
            try {
                val entity = TranscriptEntity(
                    segmentId = segmentId,
                    text = text,
                    timestamp = startTime,
                    endTimestamp = endTime,
                    speechConfidence = confidence,
                    uploadedToOmi = text.contains("Uploaded directly")
                )
                repository.insert(entity)
                _transcriptCount.value++
                Log.d(TAG, "Transcript saved: $segmentId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save transcript", e)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.MOBILE_NOTIFICATION_CHANNEL_ID,
            "Transcription Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
