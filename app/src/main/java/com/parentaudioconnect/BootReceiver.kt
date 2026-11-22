package com.santethp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restores the child's receive-only audio transport, lightweight device
 * reporting, and explicitly consented gallery P2P service after boot or update.
 * Audio receive is independent of microphone consent: this never enables capture.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SantetHP-BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = context.getSharedPreferences("parent_audio", Context.MODE_PRIVATE)
        if (prefs.getString("pair_role", null) != "child") return

        ChildDeviceStatusWorker.schedule(context)

        // Restore the receive-only audio foreground service just as the pre-split
        // app did. ACTION_CONNECT never enables the child's microphone.
        try {
            val audioIntent = Intent(context, ChildAudioConnectionService::class.java)
                .setAction(ChildAudioConnectionService.ACTION_CONNECT)
            ContextCompat.startForegroundService(context, audioIntent)
            Log.i(TAG, "Memulihkan koneksi audio penerima tanpa mengaktifkan mikrofon setelah $action")
        } catch (error: Exception) {
            Log.e(TAG, "Gagal memulihkan koneksi audio penerima setelah $action", error)
        }

        // Restore the transport when either the gallery consent was
        // acknowledged by the server or the child separately consented to
        // Drive backup. The service re-checks media access and pairing code.
        val gallerySharingEnabled =
            prefs.getBoolean("child_gallery_sharing_enabled", false)
        val serverConsentConfirmed =
            prefs.getBoolean("child_gallery_server_consent_confirmed", false)
        val backupConsent = prefs.getBoolean("child_drive_backup_consent", false)
        val parentReceiveConsent = prefs.getBoolean("child_parent_media_receive_consent", false) &&
            prefs.getBoolean("child_parent_media_server_confirmed", false)
        if ((gallerySharingEnabled && serverConsentConfirmed) || backupConsent || parentReceiveConsent) {
            try {
                val serviceIntent = Intent(context, ChildGalleryP2pService::class.java)
                    .setAction(ChildGalleryP2pService.ACTION_START)
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.i(TAG, "Memulihkan layanan galeri P2P setelah $action")
            } catch (error: Exception) {
                // Never change the saved consent state on a transient boot/startup
                // failure. The service can be started again when the app opens.
                Log.e(TAG, "Gagal memulihkan layanan galeri setelah $action", error)
            }
        }
    }
}
