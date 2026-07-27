package com.lushibaxian.pullwire

import android.app.Application
import android.util.Log

class BaXianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Cold start: only clear stale prefs/notifications here.
        // Full VPN reclaim needs a startable context (Activity / receiver).
        try {
            val repaired = ServiceRuntime.reconcile(
                this,
                "app_onCreate",
                activeCleanup = false
            )
            if (repaired) {
                Log.i(TAG, "cleared stale flags on process start")
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconcile failed: ${e.message}")
            Prefs.clearRunningFlags(this)
        }
    }

    companion object {
        private const val TAG = "BaXianApp"
    }
}
