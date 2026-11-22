package com.santethp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioPlaybackService : Service() {

    companion object {
        const val ACTION_PLAY = "com.parentaudioconnect.action.PLAY"
        const val EXTRA_FILE_PATH = "extra_file_path"
        private const val CHANNEL_ID = "parent_audio_playback"
        private const val NOTIFICATION_ID = 4101
        private const val RECOVERY_CHANNEL_ID = "parent_audio_playback_recovery"
        private const val RECOVERY_NOTIFICATION_ID = 4102
        private const val PLAYBACK_PREFS = "santethp_mp3_playback"
        private const val KEY_FILE_PATH = "active_file_path"
        private const val KEY_POSITION_MS = "last_position_ms"
    }

    private var player: MediaPlayer? = null
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action != ACTION_PLAY) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // A foreground playback service may be recreated with a null intent after
        // Android kills its process. Restore only an in-progress MP3 session saved
        // by this service; never invent a new playback session.
        val prefs = getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE)
        val restoring = intent == null
        val path = if (restoring) prefs.getString(KEY_FILE_PATH, null)
            else intent.getStringExtra(EXTRA_FILE_PATH)
        val resumePosition = if (restoring) prefs.getInt(KEY_POSITION_MS, 0) else 0
        val file = path?.let(::File)
        if (file == null || !file.isFile || !file.canRead()) {
            clearSavedPlayback()
            showPlaybackRecoveryNotification("File MP3 tidak ditemukan atau tidak dapat dibaca.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        prefs.edit()
            .putString(KEY_FILE_PATH, file.absolutePath)
            .putInt(KEY_POSITION_MS, resumePosition.coerceAtLeast(0))
            .apply()

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Memutar pesan suara"))
        } catch (error: Exception) {
            android.util.Log.e("SantetHP-MP3", "Android menolak layanan pemutar MP3", error)
            showPlaybackRecoveryNotification("Android tidak mengizinkan layanan pemutar MP3 dimulai. Buka SantetHP di HP anak lalu coba kirim ulang.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startPlayback(file, startId, resumePosition)
        // Keep playback eligible for system recreation after an unexpected process death.
        return START_STICKY
    }

    private fun startPlayback(file: File, startId: Int, resumePosition: Int = 0) {
        // Keep media volume at maximum for every received MP3.
        releasePlayer()

        try {
            val nextPlayer = MediaPlayer()
            player = nextPlayer
            nextPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // USAGE_MEDIA routes through Android's normal media output path.
            nextPlayer.setDataSource(file.absolutePath)
            nextPlayer.setOnPreparedListener { prepared ->
                try {
                    raiseMediaVolumeForPlayback()
                    if (resumePosition > 0) {
                        prepared.seekTo(resumePosition.coerceAtMost(prepared.duration))
                    }
                    prepared.start()
                    if (!prepared.isPlaying) {
                        throw IllegalStateException("MediaPlayer belum mulai memutar audio")
                    }
                    // Publish active only after MediaPlayer has actually entered playback.
                    ChildAudioActivityReporter.setMp3AudioActive(applicationContext, true)
                    startPlaybackHeartbeat()
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, buildNotification("Pesan suara sedang diputar"))
                } catch (error: Exception) {
                    android.util.Log.e("SantetHP-MP3", "MediaPlayer gagal memulai playback", error)
                    stopPlaybackHeartbeat()
                    clearSavedPlayback()
                    ChildAudioActivityReporter.setMp3AudioActive(applicationContext, false)
                    showPlaybackRecoveryNotification("MP3 sudah diterima, tetapi audio tidak berhasil mulai diputar. Coba kirim ulang dari HP orang tua.")
                    releasePlayer()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            nextPlayer.setOnCompletionListener {
                stopPlaybackHeartbeat()
                clearSavedPlayback()
                ChildAudioActivityReporter.setMp3AudioActive(applicationContext, false)
                releasePlayer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            nextPlayer.setOnErrorListener { _, what, extra ->
                android.util.Log.e("SantetHP-MP3", "MediaPlayer gagal memutar MP3: what=$what extra=$extra")
                stopPlaybackHeartbeat()
                clearSavedPlayback()
                ChildAudioActivityReporter.setMp3AudioActive(applicationContext, false)
                showPlaybackRecoveryNotification("Pemutar MP3 gagal membaca atau memutar audio. Coba kirim ulang file dari HP orang tua.")
                releasePlayer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                true
            }
            nextPlayer.prepareAsync()
        } catch (error: Exception) {
            android.util.Log.e("SantetHP-MP3", "Gagal menyiapkan pemutar MP3", error)
            stopPlaybackHeartbeat()
            clearSavedPlayback()
            ChildAudioActivityReporter.setMp3AudioActive(applicationContext, false)
            showPlaybackRecoveryNotification("MP3 diterima, tetapi pemutar gagal disiapkan. Coba kirim ulang dari HP orang tua.")
            releasePlayer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun showPlaybackRecoveryNotification(message: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        RECOVERY_CHANNEL_ID,
                        "Pemulihan pemutar MP3",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Memberi tahu jika MP3 gagal diterima atau diputar"
                    }
                )
            }
            val openApp = android.app.PendingIntent.getActivity(
                this,
                RECOVERY_NOTIFICATION_ID,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                RECOVERY_NOTIFICATION_ID,
                NotificationCompat.Builder(this, RECOVERY_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("SantetHP • MP3 belum dapat diputar")
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
            )
        } catch (notificationError: Exception) {
            android.util.Log.w("SantetHP-MP3", "Tidak dapat menampilkan notifikasi pemulihan MP3", notificationError)
        }
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SantetHP")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pemutaran pesan suara",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /** Raise the media stream to maximum whenever a received MP3 starts playing. */
    private fun raiseMediaVolumeForPlayback() {
        val audioManager = getSystemService(AudioManager::class.java)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            maximum,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun startPlaybackHeartbeat() {
        stopPlaybackHeartbeat()
        heartbeatJob = heartbeatScope.launch {
            while (isActive && player?.isPlaying == true) {
                delay(10_000)
                if (player?.isPlaying == true) {
                    getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit()
                        .putInt(KEY_POSITION_MS, player?.currentPosition ?: 0)
                        .apply()
                    ChildAudioActivityReporter.refreshMp3AudioHeartbeat(applicationContext)
                }
            }
        }
    }

    private fun getSavedFilePath(): String? =
        getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).getString(KEY_FILE_PATH, null)

    private fun clearSavedPlayback() {
        getSharedPreferences(PLAYBACK_PREFS, MODE_PRIVATE).edit().clear().apply()
    }

    private fun stopPlaybackHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun releasePlayer() {
        player?.setOnPreparedListener(null)
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        player?.release()
        player = null
    }

    override fun onDestroy() {
        stopPlaybackHeartbeat()
        clearSavedPlayback()
        ChildAudioActivityReporter.setMp3AudioActive(applicationContext, false)
        releasePlayer()
        heartbeatScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
