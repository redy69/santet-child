package com.santethp

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Parent-flavor compile-time placeholder.
 * The real camera publisher is packaged only in the Anak flavor.
 */
class RemoteCameraService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ARM = "com.santethp.camera.ARM"
        const val ACTION_START_CAMERA = "com.santethp.camera.START"
        const val ACTION_STOP_CAMERA = "com.santethp.camera.STOP"
        const val ACTION_SWITCH_CAMERA = "com.santethp.camera.SWITCH"
        const val ACTION_REVOKE_AUDIO = "com.santethp.camera.REVOKE_AUDIO"
        const val ACTION_DISARM = "com.santethp.camera.DISARM"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_SERVER_ID = "livekit_server_id"
    }
}
