package com.audiomic.app

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MicService : Service() {

    companion object {
        private const val CHANNEL_ID = "mic_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val RELAY_URL = "wss://audio-relay-z5x0.onrender.com"
        private const val SECRET = "adiisalive786"
        private const val SAMPLE_RATE = 16000
    }

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isStreaming = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Microphone service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Audio Service")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioMic::WakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(RELAY_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Authenticate as phone
                val auth = JSONObject().apply {
                    put("type", "auth")
                    put("role", "phone")
                    put("secret", SECRET)
                }
                webSocket.send(auth.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    when (data.optString("type")) {
                        "auth_ok" -> {
                            updateNotification("Ready - waiting for laptop")
                        }
                        "start" -> {
                            startStreaming()
                        }
                        "stop" -> {
                            stopStreaming()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateNotification("Connection lost - reconnecting...")
                // Simple reconnect after 5 seconds
                android.os.Handler(mainLooper).postDelayed({
                    connectWebSocket()
                }, 5000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updateNotification("Disconnected")
            }
        })
    }

    private fun startStreaming() {
        if (isStreaming.get()) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                updateNotification("Microphone error")
                return
            }

            audioRecord?.startRecording()
            isStreaming.set(true)
            updateNotification("Microphone is active")

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isStreaming.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        webSocket?.send(ByteString.of(*buffer.copyOf(read)))
                    }
                }
            }
            recordingThread?.start()

        } catch (e: SecurityException) {
            updateNotification("Permission denied")
        } catch (e: Exception) {
            updateNotification("Error: ${e.message}")
        }
    }

    private fun stopStreaming() {
        isStreaming.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
        recordingThread = null
        updateNotification("Ready - waiting for laptop")
    }

    override fun onDestroy() {
        stopStreaming()
        webSocket?.close(1000, "Service stopped")
        wakeLock?.release()
        super.onDestroy()
    }
}
