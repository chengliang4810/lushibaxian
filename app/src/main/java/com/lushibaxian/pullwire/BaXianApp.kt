package com.lushibaxian.pullwire

import android.app.Application
import android.util.Log

class BaXianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Cold start after kill/update: if prefs still say "running" but services
        // are dead, reclaim residual VPN. Auto-restart is handled by
        // PackageReplacedReceiver after updates; here we only heal flags/tunnel.
        try {
            val prefRunning = Prefs.isFloatRunning(this) || Prefs.isVpnRunning(this)
            val repaired = ServiceRuntime.reconcile(
                this,
                "app_onCreate",
                activeCleanup = false
            )
            if (repaired) {
                Log.i(TAG, "cleared stale flags on process start (wasRunning=$prefRunning)")
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
