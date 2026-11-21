package com.santethp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.android.gms.tasks.Task
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.collect
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Persistent audio transport for a paired child device.
 * The microphone stays disabled until an authenticated parent command enables
 * it during a consented, prepared foreground-service session.
 */
class ChildAudioConnectionService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.parentaudioconnect.action.CONNECT_CHILD_AUDIO"
        const val ACTION_PREPARE = "com.parentaudioconnect.action.PREPARE_CHILD_CONVERSATION"
        const val ACTION_DISCONNECT = "com.parentaudioconnect.action.DISCONNECT_CHILD_AUDIO"
        const val EXTRA_SERVER_ID = "livekit_server_id"

        @Volatile private var runningInstance: ChildAudioConnectionService? = null

        fun deliverTalkCommand(enabled: Boolean, serverId: String?): Boolean {
            val service = runningInstance ?: return false
            return service.requestTalkMicrophone(enabled, serverId)
        }
        private const val CHANNEL_ID = "child_audio_connection"
        private const val NOTIFICATION_ID = 4201
        private const val RECOVERY_CHANNEL_ID = "child_audio_recovery"
        private const val RECOVERY_NOTIFICATION_ID = 4203
        private const val WORKER_URL = "https://parent-audio-token.yudhis-r15.workers.dev/token"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val talkMicMutex = Mutex()
    private var room: Room? = null
    private var roomEventsJob: Job? = null
    private var reconnectJob: Job? = null
    private var mp3TransferManager: Mp3TransferManager? = null
    private val activeRemoteAudioTrackIds = ConcurrentHashMap.newKeySet<Int>()
    private var liveHeartbeatJob: Job? = null
    private var pairingRemovalListener: ListenerRegistration? = null
    @Volatile private var connecting = false
    @Volatile private var preferredServerId: String? = null
    @Volatile private var micForegroundPrepared = false
    @Volatile private var pendingTalkMicEnabled: Boolean? = null
    @Volatile private var childTalkMicActive = false
    @Volatile private var connectedServerId: String? = null
    private var serverSwitchJob: Job? = null
    private var processRestartDetected = false

    override fun onCreate() {
        super.onCreate()
        processRestartDetected = ChildAudioActivityReporter.beginProcessSession(applicationContext)
        runningInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android supplies a null intent when it recreates a START_STICKY service
        // after process death. Both audio services from the old process are gone,
        // so clear their last published activity flags before reconnecting receive-only.
        val processRestart = processRestartDetected || intent == null
        processRestartDetected = false
        if (processRestart) {
            childTalkMicActive = false
            pendingTalkMicEnabled = null
            activeRemoteAudioTrackIds.clear()
            stopLiveAudioHeartbeat()
        }
        if (intent?.action == ACTION_DISCONNECT) {
            serviceScope.launch {
                try { room?.localParticipant?.setMicrophoneEnabled(false) } catch (_: Exception) { }
                childTalkMicActive = false
                updateNotification("Menghentikan koneksi audio…")
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        val action = intent?.action
        if (action != null && action !in setOf(ACTION_CONNECT, ACTION_PREPARE)) {
            return START_NOT_STICKY
        }

        val prepareMicrophone = action == ACTION_PREPARE
        if (prepareMicrophone &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("SantetHP-Audio", "Conversation preparation skipped: RECORD_AUDIO not granted")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        preferredServerId = intent?.getStringExtra(EXTRA_SERVER_ID)?.takeIf { it.isNotBlank() }
            ?: preferredServerId

        val foregroundTypes = if (prepareMicrophone || micForegroundPrepared) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(if (prepareMicrophone) "Menyiapkan percakapan • mikrofon belum aktif" else "Mengaktifkan koneksi audio…"),
                    foregroundTypes
                )
            } else {
                startForeground(NOTIFICATION_ID, notification("Mengaktifkan koneksi audio…"))
            }
        } catch (error: Exception) {
            // Android 14+ may reject microphone foreground promotion if the
            // activity has already left the foreground or permission was revoked.
            // Fail closed: do not capture audio and do not crash the app process.
            Log.e("SantetHP-Audio", "Foreground audio service was not allowed to start", error)
            showForegroundStartRecoveryNotification(prepareMicrophone)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (prepareMicrophone) micForegroundPrepared = true

        watchForPairingRemoval()
        if (!connecting && room == null) {
            connecting = true
            serviceScope.launch {
                if (processRestart) {
                    try {
                        // Wait for stale Firestore activity flags to clear before
                        // reconnecting. Otherwise a late "false" write could race
                        // with a fresh "true" heartbeat from the new session.
                        ChildAudioActivityReporter.clearAfterProcessRestart(applicationContext)?.await()
                    } catch (error: Exception) {
                        Log.w("SantetHP-Audio", "Could not clear stale audio activity before reconnect", error)
                    }
                }
                connectWithRetry()
            }
        }
        // Keep the receive-only audio transport eligible for recovery after process death.
        // Android restarts a START_STICKY service with a null intent: on that fresh
        // instance micForegroundPrepared is false, so it is promoted only as
        // CONNECTED_DEVICE and reconnects MP3/Live Audio receive transport. Microphone
        // capture is NOT restored; the child must reopen SantetHP to prepare it again.
        return START_STICKY
    }

    private fun watchForPairingRemoval() {
        val prefs = getSharedPreferences("parent_audio", MODE_PRIVATE)
        val code = prefs.getString("pair_code", null)
        if (code.isNullOrBlank() || prefs.getString("pair_role", null) != "child") return
        pairingRemovalListener?.remove()
        pairingRemovalListener = FirebaseFirestore.getInstance()
            .collection("pairings")
            .document(code)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null &&
                    !snapshot.metadata.isFromCache && !snapshot.exists()
                ) {
                    prefs.edit()
                        .remove("pair_role")
                        .remove("pair_code")
                        .putBoolean("remote_camera_armed", false)
                        .apply()
                    ChildDeviceStatusWorker.cancel(applicationContext)
                    stopService(Intent(this, RemoteCameraService::class.java))
                    stopService(Intent(this, AudioPlaybackService::class.java))
                    updateNotification("Hubungan dengan orang tua telah dilepaskan")
                    stopSelf()
                }
            }
    }

    private suspend fun connectWithRetry() {
        var attempt = 0
        try {
            while (true) {
                var connectionStage = "Persiapan"
                try {
                    val prefs = getSharedPreferences("parent_audio", MODE_PRIVATE)
                    val code = prefs.getString("pair_code", null)
                    val role = prefs.getString("pair_role", null)
                    if (code.isNullOrBlank() || role != "child") {
                        updateNotification("Belum ada pasangan perangkat tersimpan")
                        stopSelf()
                        return
                    }

                    connectionStage = "Autentikasi Firebase"
                    val auth = FirebaseAuth.getInstance()
                    val user = auth.currentUser ?: auth.signInAnonymously().await().user
                        ?: throw IllegalStateException("Autentikasi Firebase gagal")

                    connectionStage = "Mengambil token Firebase"
                    val idToken = user.getIdToken(false).await().token
                        ?: throw IllegalStateException("Token Firebase kosong")

                    connectionStage = "Meminta token server"
                    updateNotification("Menghubungkan ke ruang audio…")
                    val response = withContext(Dispatchers.IO) {
                        requestToken(idToken, code, preferredServerId)
                    }

                    connectionStage = "Memvalidasi akses server"
                    val returnedRole = response.optString("role")
                    if (returnedRole != "child") {
                        throw IllegalStateException("Server tidak mengembalikan akses anak")
                    }

                    connectionStage = "Menghubungkan LiveKit"
                    val liveKitConnection = LiveKitFailover.connectWithServer(
                        response = response,
                        createRoom = {
                            LiveKit.create(
                                appContext = applicationContext,
                                overrides = LiveKitOverrides(
                                    audioOptions = AudioOptions(
                                        audioOutputType = AudioType.MediaAudioType()
                                    )
                                )
                            )
                        },
                        prepareRoom = { candidateRoom ->
                        // Track the in-flight candidate before connect so a disconnect during
                        // the handshake cannot be mistaken for a healthy connected room.
                        room = candidateRoom
                        roomEventsJob?.cancel()
                        roomEventsJob = serviceScope.launch {
                            candidateRoom.events.collect { event ->
                                when (event) {
                                    is RoomEvent.TrackSubscribed -> {
                                        val remoteTrack = event.track as? RemoteAudioTrack
                                        if (remoteTrack != null) {
                                            activeRemoteAudioTrackIds.add(remoteTrack.hashCode())
                                            maximizeMediaVolume()
                                            try { remoteTrack.setVolume(2.0) } catch (_: Exception) { }
                                            ChildAudioActivityReporter.setLiveAudioActive(
                                                applicationContext, true
                                            )
                                            updateNotification(
                                                if (childTalkMicActive) "Percakapan aktif • suara orang tua diterima"
                                                else "Audio live sedang diputar • mikrofon anak mati"
                                            )
                                            startLiveAudioHeartbeat()
                                        }
                                    }
                                    is RoomEvent.TrackMuted -> {
                                        if (event.publication.kind == Track.Kind.AUDIO) {
                                            // Keep other subscribed audio tracks active if any remain.
                                            val mutedTrack = event.publication.track
                                            if (mutedTrack is RemoteAudioTrack) {
                                                activeRemoteAudioTrackIds.remove(mutedTrack.hashCode())
                                            } else {
                                                activeRemoteAudioTrackIds.clear()
                                            }
                                            val stillActive = childTalkMicActive || activeRemoteAudioTrackIds.isNotEmpty()
                                            if (!stillActive) stopLiveAudioHeartbeat()
                                            ChildAudioActivityReporter.setLiveAudioActive(
                                                applicationContext, stillActive
                                            )
                                            updateNotification(
                                                when {
                                                    childTalkMicActive -> "Percakapan aktif • mikrofon anak menyala"
                                                    stillActive -> "Audio live masih diputar"
                                                    else -> "Audio live berhenti • menunggu orang tua"
                                                }
                                            )
                                        }
                                    }
                                    is RoomEvent.TrackUnmuted -> {
                                        val unmutedTrack = event.publication.track
                                        if (event.publication.kind == Track.Kind.AUDIO &&
                                            unmutedTrack is RemoteAudioTrack
                                        ) {
                                            activeRemoteAudioTrackIds.add(unmutedTrack.hashCode())
                                            maximizeMediaVolume()
                                            try { unmutedTrack.setVolume(2.0) } catch (_: Exception) { }
                                            ChildAudioActivityReporter.setLiveAudioActive(
                                                applicationContext, true
                                            )
                                            updateNotification(
                                                if (childTalkMicActive) "Percakapan aktif • suara orang tua diterima"
                                                else "Audio live sedang diputar • mikrofon anak mati"
                                            )
                                            startLiveAudioHeartbeat()
                                        }
                                    }
                                    is RoomEvent.TrackUnsubscribed -> {
                                        if (event.track is RemoteAudioTrack) {
                                            activeRemoteAudioTrackIds.remove(event.track.hashCode())
                                            val audioActive = childTalkMicActive || activeRemoteAudioTrackIds.isNotEmpty()
                                            if (!audioActive) stopLiveAudioHeartbeat()
                                            ChildAudioActivityReporter.setLiveAudioActive(
                                                applicationContext, audioActive
                                            )
                                            updateNotification(
                                                when {
                                                    childTalkMicActive -> "Percakapan aktif • mikrofon anak menyala"
                                                    audioActive -> "Audio live masih diputar"
                                                    else -> "Audio live berhenti • koneksi tetap aktif"
                                                }
                                            )
                                            // Keep the requested maximum volume after each audio session.
                                        }
                                    }
                                    is RoomEvent.ParticipantDisconnected -> {
                                        // In this one-parent/one-child room, losing the parent
                                        // ends the authorized conversation. Never leave the
                                        // child's microphone publishing after the parent leaves.
                                        if (childTalkMicActive) {
                                            pendingTalkMicEnabled = false
                                            serviceScope.launch {
                                                applyPendingTalkMicrophoneState()
                                                updateNotification("Orang tua terputus • mikrofon anak dimatikan")
                                            }
                                        }
                                    }
                                    is RoomEvent.Disconnected -> {
                                        if (room === candidateRoom) {
                                            room = null
                                            activeRemoteAudioTrackIds.clear()
                                            // Preserve an explicit STOP command across reconnects.
                                            // Never turn a failed stop into a later microphone re-enable.
                                            if (childTalkMicActive && pendingTalkMicEnabled != false) {
                                                pendingTalkMicEnabled = true
                                            }
                                            childTalkMicActive = false
                                            stopLiveAudioHeartbeat()
                                            ChildAudioActivityReporter.setLiveAudioActive(
                                                applicationContext, false
                                            )
                                            updateNotification("Koneksi audio terputus • mencoba kembali")
                                            // Queue recovery even during the connect/finally window.
                                            // The recovery job waits until this loop has finished.
                                            scheduleAudioReconnect(candidateRoom)
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
    
    
                        }
                    )
                    val newRoom = liveKitConnection.room
                    connectedServerId = liveKitConnection.serverId
                    if (room !== newRoom) {
                        // A disconnect may arrive immediately after connect() succeeds.
                        try { newRoom.disconnect() } catch (_: Exception) { }
                        try { newRoom.release() } catch (_: Exception) { }
                        throw IllegalStateException("Koneksi audio terputus saat proses penyambungan")
                    }
                    connectionStage = "Menyiapkan audio"
                    newRoom.localParticipant.setMicrophoneEnabled(false)
                    // LiveKit MediaAudioType uses Android's normal media route and STREAM_MUSIC.
                    maximizeMediaVolume()
                    room = newRoom
                    mp3TransferManager = Mp3TransferManager(
                        context = applicationContext,
                        room = newRoom,
                        scope = serviceScope,
                        onStatus = { message ->
                            // onReceived starts playback and sets its own notification.
                            // Do not overwrite that state with the generic transfer-complete text.
                            if (message != "MP3 berhasil diterima!") updateNotification(message)
                        },
                        onReceived = { file ->
                            maximizeMediaVolume()
                            val playbackIntent = Intent(applicationContext, AudioPlaybackService::class.java).apply {
                                action = AudioPlaybackService.ACTION_PLAY
                                putExtra(AudioPlaybackService.EXTRA_FILE_PATH, file.absolutePath)
                            }
                            try {
                                androidx.core.content.ContextCompat.startForegroundService(applicationContext, playbackIntent)
                                updateNotification("MP3 diterima • memutar audio")
                            } catch (error: Exception) {
                                updateNotification("MP3 diterima, gagal memutar: ${error.localizedMessage}")
                            }
                        }
                    )
                    updateNotification(
                        if (micForegroundPrepared) "Siap menerima percakapan • mikrofon anak mati • MP3 aktif"
                        else "Terhubung • audio live dan MP3 aktif • mikrofon mati"
                    )
                    pendingTalkMicEnabled?.let { desiredMicState ->
                        serviceScope.launch { applyPendingTalkMicrophoneState() }
                    }
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    roomEventsJob?.cancel()
                    roomEventsJob = null
                    room = null
                    connectedServerId = null
                    // Keep a pending STOP authoritative; a reconnect must not
                    // resurrect microphone capture after the parent has stopped it.
                    if (childTalkMicActive && pendingTalkMicEnabled != false) {
                        pendingTalkMicEnabled = true
                    }
                    childTalkMicActive = false
                    mp3TransferManager = null
                    activeRemoteAudioTrackIds.clear()
                    stopLiveAudioHeartbeat()
                    ChildAudioActivityReporter.setLiveAudioActive(applicationContext, false)
                    attempt++
                    val errorDetails = generateSequence(error as Throwable?) { it.cause }
                        .take(4)
                        .map { cause ->
                            cause.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: cause.javaClass.simpleName
                        }
                        .joinToString(" <- ")
                        .take(150)
                    val diagnostic = "Gagal $connectionStage: ${error.javaClass.simpleName}: $errorDetails"
                    Log.e("SantetHP-Audio", diagnostic, error)
                    getSharedPreferences("parent_audio", MODE_PRIVATE).edit()
                        .putString("child_audio_last_connection_error", diagnostic)
                        .putLong("child_audio_last_connection_error_at", System.currentTimeMillis())
                        .apply()
                    updateNotification("$diagnostic • mencoba lagi")
                    // Retry quickly after boot/network startup, then keep a short steady backoff.
                    val retryDelayMs = when {
                        attempt == 1 -> 1_000L
                        attempt == 2 -> 2_000L
                        attempt == 3 -> 4_000L
                        else -> 8_000L
                    }
                    delay(retryDelayMs)
                }
            }
        } finally {
            connecting = false
        }
    }

    private fun scheduleAudioReconnect(disconnectedRoom: Room) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = serviceScope.launch {
            delay(1_500)
            // A disconnect can arrive just before connectWithRetry() exits.
            // Wait for that loop to finish instead of losing the retry request.
            while (connecting && isActive) delay(500)
            // Abort if a newer candidate has already become the active room.
            if (!isActive || room != null) return@launch

            roomEventsJob?.cancel()
            roomEventsJob = null
            mp3TransferManager = null
            try {
                disconnectedRoom.disconnect()
            } catch (_: Exception) { }
            try {
                disconnectedRoom.release()
            } catch (_: Exception) { }

            if (!connecting && isActive && room == null) {
                connecting = true
                connectWithRetry()
            }
        }
    }

    private fun requestTalkMicrophone(enabled: Boolean, serverId: String?): Boolean {
        if (!micForegroundPrepared ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return false

        pendingTalkMicEnabled = enabled
        if (!serverId.isNullOrBlank() && connectedServerId != serverId) {
            preferredServerId = serverId
            reconnectForConversationServer(serverId)
        } else {
            serviceScope.launch { applyPendingTalkMicrophoneState() }
        }
        return true
    }

    private fun reconnectForConversationServer(serverId: String) {
        if (serverSwitchJob?.isActive == true) return
        serverSwitchJob = serviceScope.launch {
            while (connecting && isActive) delay(250)
            if (!isActive) return@launch
            val activeRoom = room
            if (activeRoom != null && connectedServerId != serverId) {
                try { activeRoom.localParticipant.setMicrophoneEnabled(false) } catch (_: Exception) { }
                childTalkMicActive = false
                roomEventsJob?.cancel()
                roomEventsJob = null
                mp3TransferManager = null
                room = null
                try { activeRoom.disconnect() } catch (_: Exception) { }
                try { activeRoom.release() } catch (_: Exception) { }
                connectedServerId = null
            }
            if (room == null && !connecting && isActive) {
                connecting = true
                connectWithRetry()
            } else if (room != null && isActive) {
                applyPendingTalkMicrophoneState()
            }
        }
    }

    private suspend fun applyPendingTalkMicrophoneState() {
        // Serialize LiveKit microphone transitions. If START and STOP arrive while
        // one transition is in flight, always apply the newest requested state last.
        talkMicMutex.withLock {
            var desiredState = pendingTalkMicEnabled ?: return@withLock
            while (true) {
                val activeRoom = room
                if (activeRoom == null) {
                    pendingTalkMicEnabled = desiredState
                    updateNotification(
                        if (desiredState) "Menyiapkan ruang percakapan • mikrofon belum aktif"
                        else "Mikrofon anak mati • menunggu orang tua"
                    )
                    return@withLock
                }

                if (childTalkMicActive == desiredState) {
                    if (pendingTalkMicEnabled == desiredState) pendingTalkMicEnabled = null
                    return@withLock
                }

                try {
                    var changed = activeRoom.localParticipant.setMicrophoneEnabled(desiredState)
                    if (!changed && pendingTalkMicEnabled == desiredState) {
                        // Retry one transient LiveKit failure, unless a newer command
                        // arrived or the room changed while the transition was pending.
                        delay(600)
                        if (pendingTalkMicEnabled != desiredState) {
                            desiredState = pendingTalkMicEnabled ?: return@withLock
                            continue
                        }
                        if (room === activeRoom && (!desiredState || micForegroundPrepared)) {
                            changed = activeRoom.localParticipant.setMicrophoneEnabled(desiredState)
                        }
                    }

                    if (room !== activeRoom) return@withLock
                    // Record a completed transition even if a newer command arrived
                    // during the LiveKit call; the loop below immediately applies it.
                    if (changed) childTalkMicActive = desiredState
                    if (pendingTalkMicEnabled != desiredState) {
                        desiredState = pendingTalkMicEnabled ?: return@withLock
                        continue
                    }

                    if (!changed) {
                        updateNotification(
                            if (desiredState) "Mikrofon percakapan belum berhasil aktif • menunggu koneksi stabil"
                            else "Mikrofon belum berhasil dimatikan • mencoba kembali saat tersambung"
                        )
                        return@withLock
                    }

                    childTalkMicActive = desiredState
                    pendingTalkMicEnabled = null
                    val audioActive = desiredState || activeRemoteAudioTrackIds.isNotEmpty()
                    ChildAudioActivityReporter.setLiveAudioActive(applicationContext, audioActive)
                    if (audioActive) startLiveAudioHeartbeat() else stopLiveAudioHeartbeat()
                    updateNotification(
                        if (desiredState) "Percakapan aktif • mikrofon anak menyala"
                        else if (activeRemoteAudioTrackIds.isNotEmpty()) "Suara orang tua aktif • mikrofon anak mati"
                        else "Siap • mikrofon anak mati • MP3 tetap terhubung"
                    )
                    return@withLock
                } catch (error: Exception) {
                    Log.w("SantetHP-Audio", "Could not change child conversation microphone", error)
                    if (pendingTalkMicEnabled != desiredState) {
                        desiredState = pendingTalkMicEnabled ?: return@withLock
                        continue
                    }
                    updateNotification("Gagal mengubah mikrofon percakapan")
                    return@withLock
                }
            }
        }
    }

    /** Keep LiveKit listener output at maximum on the Android media stream. */
    private fun maximizeMediaVolume() {
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        val maximum = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            maximum,
            android.media.AudioManager.FLAG_SHOW_UI
        )
    }

    private fun startLiveAudioHeartbeat() {
        if (liveHeartbeatJob?.isActive == true) return
        liveHeartbeatJob = serviceScope.launch {
            // The heartbeat represents either direction of the live conversation:
            // incoming parent audio OR the child's explicitly enabled microphone.
            while (isActive && (activeRemoteAudioTrackIds.isNotEmpty() || childTalkMicActive)) {
                delay(10_000)
                if (activeRemoteAudioTrackIds.isNotEmpty() || childTalkMicActive) {
                    ChildAudioActivityReporter.refreshLiveAudioHeartbeat(applicationContext)
                }
            }
        }
    }

    private fun stopLiveAudioHeartbeat() {
        liveHeartbeatJob?.cancel()
        liveHeartbeatJob = null
    }

    private fun requestToken(idToken: String, code: String, preferredServerId: String?): JSONObject {
        val connection = URL(WORKER_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().apply {
                put("idToken", idToken)
                put("code", code)
                put("mode", "conversation")
                if (!preferredServerId.isNullOrBlank()) put("preferredServerId", preferredServerId)
            }
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val response = JSONObject(responseText)
            if (status !in 200..299) {
                throw IllegalStateException(response.optString("error", "Server menolak koneksi"))
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun showForegroundStartRecoveryNotification(microphoneRequired: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    RECOVERY_CHANNEL_ID,
                    "Pemulihan layanan audio",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Petunjuk saat Android menghentikan layanan audio SantetHP"
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            RECOVERY_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = if (microphoneRequired) {
            "Layanan percakapan belum dapat disiapkan oleh Android. Buka SantetHP di HP anak agar izin dan layanan mikrofon disiapkan kembali. Mikrofon tetap mati sampai orang tua memulai percakapan."
        } else {
            "Layanan penerima audio belum dapat dimulai. Buka SantetHP di HP anak untuk menyambungkan ulang layanan audio."
        }
        val notification = NotificationCompat.Builder(this, RECOVERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("SantetHP • Layanan audio perlu dipulihkan")
            .setContentText(if (microphoneRequired) "Buka SantetHP untuk menyiapkan percakapan." else "Buka SantetHP untuk menyambungkan audio kembali.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(RECOVERY_NOTIFICATION_ID, notification)
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("SantetHP")
            .setContentText(message)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Koneksi audio perangkat anak",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menampilkan status koneksi audio yang dipulihkan otomatis"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        pairingRemovalListener?.remove()
        pairingRemovalListener = null
        reconnectJob?.cancel()
        reconnectJob = null
        serverSwitchJob?.cancel()
        serverSwitchJob = null
        micForegroundPrepared = false
        childTalkMicActive = false
        pendingTalkMicEnabled = null
        connectedServerId = null
        if (runningInstance === this) runningInstance = null
        roomEventsJob?.cancel()
        roomEventsJob = null
        activeRemoteAudioTrackIds.clear()
        stopLiveAudioHeartbeat()
        ChildAudioActivityReporter.setLiveAudioActive(applicationContext, false)
        mp3TransferManager = null
        serviceScope.cancel()
        val activeRoom = room
        room = null
        if (activeRoom != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    activeRoom.disconnect()
                } finally {
                    activeRoom.release()
                }
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Operasi Firebase gagal")
                )
            }
        }
    }
