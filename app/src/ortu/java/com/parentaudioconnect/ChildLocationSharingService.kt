package com.santethp

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Parent-flavor compile-time placeholder.
 * Location sharing runs only in the Anak flavor.
 */
class ChildLocationSharingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.santethp.action.START_LOCATION_SHARING"
        const val ACTION_STOP = "com.santethp.action.STOP_LOCATION_SHARING"
    }
}
