package com.lushibaxian.pullwire

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired after this package is updated in place (adb install -r / store update).
 * Old VPN process is dead; system may still show a stale VPN until we clean up.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "MY_PACKAGE_REPLACED → forceStopAll")
        ServiceRuntime.forceStopAll(context.applicationContext)
    }

    companion object {
        private const val TAG = "PackageReplaced"
    }
}
