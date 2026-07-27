package com.lushibaxian.pullwire

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lushibaxian.pullwire.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    /** After VPN consent returns OK, continue start + launch game. */
    private var pendingStartAfterVpn = false

    private val latencyTicker = object : Runnable {
        override fun run() {
            LatencyProbe.refreshAsync { rtt -> updateLatencyUi(rtt) }
            uiHandler.postDelayed(this, LatencyProbe.INTERVAL_MS)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (pendingStartAfterVpn) {
                pendingStartAfterVpn = false
                startAndLaunchGame()
            }
        } else {
            pendingStartAfterVpn = false
            Toast.makeText(this, R.string.toast_vpn_denied, Toast.LENGTH_SHORT).show()
        }
        refreshUi()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets()

        binding.btnToggle.setOnClickListener { onPrimaryAction() }
        binding.btnGrant.setOnClickListener { onGrantClicked() }
        binding.rowOverlay.setOnClickListener { if (!hasOverlay()) requestOverlay() }
        binding.rowVpn.setOnClickListener { if (!hasVpnConsent()) requestVpn(startAfter = false) }
        binding.rowGame.setOnClickListener {
            if (!isHsInstalled()) {
                Toast.makeText(this, R.string.toast_game_missing, Toast.LENGTH_SHORT).show()
            }
        }
        binding.panelLatency.setOnClickListener {
            binding.tvLatency.text = getString(R.string.latency_measuring)
            LatencyProbe.refreshAsync { rtt -> updateLatencyUi(rtt) }
        }

        maybeRequestNotificationPermission()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        binding.tvLatency.text = getString(R.string.latency_measuring)
        LatencyProbe.refreshAsync { rtt -> updateLatencyUi(rtt) }
        uiHandler.removeCallbacks(latencyTicker)
        uiHandler.postDelayed(latencyTicker, LatencyProbe.INTERVAL_MS)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(latencyTicker)
        super.onPause()
    }

    private fun applySystemInsets() {
        val baseStart = binding.root.paddingStart
        val baseEnd = binding.root.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Extra 12dp below status bar so title is fully visible.
            val extraTop = (12 * resources.displayMetrics.density).toInt()
            val extraBottom = (8 * resources.displayMetrics.density).toInt()
            v.setPadding(
                baseStart,
                bars.top + extraTop,
                baseEnd,
                bars.bottom + extraBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateLatencyUi(rttMs: Long) {
        if (!::binding.isInitialized) return
        if (rttMs < 0) {
            binding.tvLatency.text = getString(R.string.latency_unknown)
            binding.tvLatency.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        } else {
            binding.tvLatency.text = getString(R.string.latency_value, rttMs.toInt())
            val color = when {
                rttMs < 80 -> R.color.signal_live
                rttMs < 150 -> R.color.accent_copper
                else -> R.color.signal_warn
            }
            binding.tvLatency.setTextColor(ContextCompat.getColor(this, color))
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun hasVpnConsent(): Boolean = VpnService.prepare(this) == null

    private fun isHsInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(Prefs.HS_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun onPrimaryAction() {
        if (Prefs.isFloatRunning(this)) {
            stopService()
        } else {
            tryStart()
        }
    }

    private fun tryStart() {
        if (!hasOverlay()) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_SHORT).show()
            requestOverlay()
            return
        }
        if (!hasVpnConsent()) {
            Toast.makeText(this, R.string.toast_need_vpn, Toast.LENGTH_SHORT).show()
            requestVpn(startAfter = true)
            return
        }
        startAndLaunchGame()
    }

    private fun startAndLaunchGame() {
        if (!isHsInstalled()) {
            Toast.makeText(this, R.string.toast_game_missing, Toast.LENGTH_SHORT).show()
        }

        PullWireController.startVpn(this)
        val intent = Intent(this, FloatBallService::class.java).apply {
            action = FloatBallService.ACTION_SHOW
        }
        ContextCompat.startForegroundService(this, intent)
        Prefs.setFloatRunning(this, true)
        refreshUi()

        launchHearthstone()
        moveTaskToBack(true)
    }

    private fun stopService() {
        val intent = Intent(this, FloatBallService::class.java).apply {
            action = FloatBallService.ACTION_HIDE
        }
        startService(intent)
        PullWireController.stopVpn(this)
        Prefs.setFloatRunning(this, false)
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun launchHearthstone() {
        val launch = packageManager.getLaunchIntentForPackage(Prefs.HS_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launch)
            } catch (_: Exception) {
                // ignore; service is already running
            }
        }
    }

    private fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestVpn(startAfter: Boolean) {
        pendingStartAfterVpn = startAfter
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            pendingStartAfterVpn = false
            if (startAfter) startAndLaunchGame()
            refreshUi()
        } else {
            vpnPermissionLauncher.launch(prepare)
        }
    }

    private fun onGrantClicked() {
        when {
            !hasOverlay() -> requestOverlay()
            !hasVpnConsent() -> requestVpn(startAfter = false)
        }
    }

    private fun refreshUi() {
        val overlayOk = hasOverlay()
        val vpnOk = hasVpnConsent()
        val hsOk = isHsInstalled()
        val running = Prefs.isFloatRunning(this)

        binding.tvAction.text = getString(
            if (running) R.string.action_stop else R.string.action_start
        )
        binding.tvStatus.text = getString(
            if (running) R.string.status_running else R.string.status_idle
        )
        binding.tvStatusHint.text = getString(
            if (running) R.string.status_hint_running else R.string.status_hint_idle
        )
        binding.viewSignal.setBackgroundResource(
            if (running) R.drawable.bg_status_orb_on else R.drawable.bg_status_orb_off
        )
        binding.btnToggle.setBackgroundResource(
            if (running) R.drawable.bg_power_on else R.drawable.bg_power_off
        )
        binding.tvAction.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.signal_live else R.color.text_primary
            )
        )
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.signal_live else R.color.text_secondary
            )
        )

        setRowState(binding.tvOverlayState, overlayOk, getString(R.string.perm_ok), getString(R.string.perm_need))
        setRowState(binding.tvVpnState, vpnOk, getString(R.string.perm_ok), getString(R.string.perm_need))
        setRowState(binding.tvGameState, hsOk, getString(R.string.perm_ok), getString(R.string.perm_missing))

        when {
            !overlayOk -> {
                binding.btnGrant.visibility = View.VISIBLE
                binding.btnGrant.setText(R.string.perm_grant_overlay)
            }
            !vpnOk -> {
                binding.btnGrant.visibility = View.VISIBLE
                binding.btnGrant.setText(R.string.perm_grant_vpn)
            }
            else -> binding.btnGrant.visibility = View.GONE
        }

        val cached = LatencyProbe.lastRttMs()
        if (cached >= 0) updateLatencyUi(cached)
    }

    private fun setRowState(view: TextView, ok: Boolean, okText: String, badText: String) {
        view.text = if (ok) okText else badText
        view.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.signal_live else R.color.signal_warn)
        )
    }
}
