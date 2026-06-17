package com.scrollguard

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.scrollguard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateUI() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()
        setSupportActionBar(binding.toolbar)

        loadSavedConfig()
        setupListeners()
        checkNotificationPermission()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun loadSavedConfig() {
        TimerState.load(this)
        binding.etFreeMin.setText((TimerState.freeDuration / 60).toString())
        binding.etLockMin.setText((TimerState.lockDuration / 60).toString())
        binding.etAllowMin.setText((TimerState.allowDuration / 60).toString())
        if (TimerState.strictness == TimerState.Strictness.GENTLE) {
            binding.toggleStrictness.check(R.id.btnGentle)
        } else {
            binding.toggleStrictness.check(R.id.btnNuclear)
        }
    }

    private fun setupListeners() {
        binding.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()))
        }

        binding.btnSetup.setOnClickListener { showAccessibilityHelpDialog() }

        binding.btnBattery.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
        }

        binding.btnApps.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, UsageStatsActivity::class.java))
        }

        binding.toggleStrictness.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                TimerState.strictness = if (checkedId == R.id.btnGentle)
                    TimerState.Strictness.GENTLE else TimerState.Strictness.NUCLEAR
                TimerState.save(this)
            }
        }

        binding.switchStrict.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isDeviceAdminActive()) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this, AdminReceiver::class.java))
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_description))
                startActivity(intent)
            }
            TimerState.strictMode = isChecked
            TimerState.save(this)
        }

        binding.btnStart.setOnClickListener {
            if (TimerState.monitoredApps.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_select_apps), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // FIX M2: Validate and clamp duration inputs to safe range
            val freeRaw = binding.etFreeMin.text.toString().toLongOrNull() ?: 60
            val lockRaw = binding.etLockMin.text.toString().toLongOrNull() ?: 10
            val allowRaw = binding.etAllowMin.text.toString().toLongOrNull() ?: 2
            val free = TimerState.clampDuration(freeRaw)
            val lock = TimerState.clampDuration(lockRaw)
            val allow = TimerState.clampDuration(allowRaw)

            // Update UI to reflect clamped values if they were changed
            if (free != freeRaw) binding.etFreeMin.setText(free.toString())
            if (lock != lockRaw) binding.etLockMin.setText(lock.toString())
            if (allow != allowRaw) binding.etAllowMin.setText(allow.toString())

            TimerState.freeDuration = free * 60
            TimerState.lockDuration = lock * 60
            TimerState.allowDuration = allow * 60
            TimerState.save(this)
            checkAndStartTracking()
        }

        binding.btnReset.setOnClickListener {
            startActivity(Intent(this, PinActivity::class.java))
        }
    }

    private fun showAccessibilityHelpDialog() {
        val message = StringBuilder()
        message.append("1. Find 'ScrollGuard Blocker' in the list.\n")
        message.append("2. Turn the switch ON.\n\n")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            message.append("⚠️ If 'Restricted Setting' appears:\n")
            message.append("Go to App Info > Three Dots (top right) > 'Allow restricted settings', then try again.\n\n")
        }
        
        message.append("Note: If it's already ON but showing 'Not Working', try turning it OFF and then ON again.")

        AlertDialog.Builder(this)
            .setTitle("Accessibility Setup")
            .setMessage(message.toString())
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndStartTracking() {
        when {
            !Settings.canDrawOverlays(this) ->
                Toast.makeText(this, getString(R.string.toast_overlay_required), Toast.LENGTH_LONG).show()
            !isAccessibilityEnabled() ->
                Toast.makeText(this, getString(R.string.toast_accessibility_required), Toast.LENGTH_LONG).show()
            else -> {
                val i = Intent(this, TimerService::class.java).apply { action = "START" }
                startForegroundService(i)
                updateUI()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TimerState.load(applicationContext)
        val filter = IntentFilter("com.scrollguard.TICK")
        ContextCompat.registerReceiver(this, tickReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // FIX C5: Send explicit "RESUME" action so TimerService.onStartCommand
        // knows this is a reconnect, not a fresh start. Without an action, the
        // old code fell through to startForeground() which could time out.
        if (TimerState.isRunning()) {
            val i = Intent(this, TimerService::class.java).apply { action = "RESUME" }
            startForegroundService(i)
        }

        // FIX H3: Always sync switch state with actual device admin status.
        // Previously, if the user toggled the switch ON but cancelled the
        // device admin dialog, strictMode stayed true with no protection.
        val adminActive = isDeviceAdminActive()
        binding.switchStrict.isChecked = adminActive
        if (TimerState.strictMode != adminActive) {
            TimerState.strictMode = adminActive
            TimerState.save(this)
        }

        updateUI()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
    }

    private fun updateUI() {
        TimerState.load(applicationContext)

        val accessOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)

        binding.cardPermissions.visibility = if (accessOk && overlayOk && batteryOk) View.GONE else View.VISIBLE
        binding.btnSetup.visibility   = if (accessOk)   View.GONE else View.VISIBLE
        binding.btnOverlay.visibility = if (overlayOk)  View.GONE else View.VISIBLE
        binding.btnBattery.visibility = if (batteryOk)  View.GONE else View.VISIBLE

        val remaining = TimerState.getRemainingSeconds()
        val phase = TimerState.phase

        binding.tvPhase.text = when (phase) {
            TimerState.Phase.IDLE    -> getString(R.string.ready)
            TimerState.Phase.FREE    -> TimerState.Phase.FREE.name
            TimerState.Phase.LOCKED  -> TimerState.Phase.LOCKED.name
            TimerState.Phase.ALLOWED -> TimerState.Phase.ALLOWED.name
        }
        binding.tvTimer.text = TimerState.fmtTime(remaining)

        when (phase) {
            TimerState.Phase.IDLE -> {
                binding.tvSub.text = getString(R.string.select_apps_start)
                binding.btnStart.visibility  = View.VISIBLE
                binding.btnReset.visibility  = View.GONE
                binding.etFreeMin.isEnabled  = true
                binding.etLockMin.isEnabled  = true
                binding.etAllowMin.isEnabled = true
                binding.btnGentle.isEnabled  = true
                binding.btnNuclear.isEnabled = true
            }
            else -> {
                binding.tvSub.text = when (phase) {
                    TimerState.Phase.FREE    -> getString(R.string.enjoy_freely)
                    TimerState.Phase.LOCKED  -> getString(R.string.locked_put_phone_down)
                    TimerState.Phase.ALLOWED -> getString(R.string.quick_window)
                    else -> ""
                }
                binding.btnStart.visibility  = View.GONE
                binding.btnReset.visibility  = View.VISIBLE
                binding.etFreeMin.isEnabled  = false
                binding.etLockMin.isEnabled  = false
                binding.etAllowMin.isEnabled = false
                binding.btnGentle.isEnabled  = false
                binding.btnNuclear.isEnabled = false
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val expectedName = BlockerAccessibilityService::class.java.name
        
        for (service in enabledServices) {
            val info = service.resolveInfo.serviceInfo
            if (info.packageName == packageName && info.name == expectedName) {
                return true
            }
        }
        return false
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }
}
