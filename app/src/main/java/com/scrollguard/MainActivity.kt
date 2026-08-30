package com.scrollguard

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.scrollguard.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIF_PERMISSION_REQUEST_CODE = 101
    }

    private lateinit var binding: ActivityMainBinding

    // Extracted so it can be detached/reattached when we programmatically sync the switch to
    // the real Device Admin state (see syncStrictModeSwitch) without re-triggering itself.
    private val strictModeListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        onStrictModeToggled(isChecked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()

        loadSavedConfig()
        setupListeners()

        // First-ever launch (or first launch after updating from a version without this guide):
        // explain what ScrollGuard does and why it needs each permission before asking for any
        // of them — previously the very first thing a new user saw was a bare system
        // notification-permission dialog with zero context. Once the guide has been shown, it's
        // never auto-launched again (it stays reachable from the permissions card below).
        if (!SetupGuideActivity.hasSeenGuide(this)) {
            startActivity(Intent(this, SetupGuideActivity::class.java))
        } else {
            checkNotificationPermission()
        }

        // Replaces the old "com.scrollguard.TICK" broadcast receiver with a direct collection
        // of TimerState's in-process StateFlow. repeatOnLifecycle handles start/stop for us,
        // so there's no manual register/unregister to forget.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                TimerState.tickSignal.collect { updateUI() }
            }
        }
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun loadSavedConfig() {
        TimerState.load(this)
        binding.tvFreeMin.text = (TimerState.freeDuration / 60).toString()
        binding.tvLockMin.text = (TimerState.lockDuration / 60).toString()
        binding.tvAllowMin.text = (TimerState.allowDuration / 60).toString()
        if (TimerState.strictness == TimerState.Strictness.GENTLE) {
            binding.toggleStrictness.check(R.id.btnGentle)
            binding.tvModeDescription.text = getString(R.string.mode_desc_gentle)
        } else {
            binding.toggleStrictness.check(R.id.btnNuclear)
            binding.tvModeDescription.text = getString(R.string.mode_desc_nuclear)
        }
    }

    /** Returns false if the tap had no effect (already at the min/max clamp), so callers can
     *  give a distinct "denied" haptic instead of silently doing nothing. */
    private fun adjustTimer(tv: TextView, delta: Int): Boolean {
        val current = tv.text.toString().toIntOrNull() ?: 0
        val next = (current + delta).coerceIn(
            TimerState.MIN_DURATION_MIN.toInt(), TimerState.MAX_DURATION_MIN.toInt()
        )
        tv.text = next.toString()
        return next != current
    }

    /** Haptic feedback for a stepper tap that had no effect (already at its min/max clamp).
     *  REJECT was only added in API 30; older devices just get no extra feedback for this case
     *  (they still got the normal CLOCK_TICK from the caller). */
    private fun performClampedFeedback(v: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
        }
    }

    /** Direct numeric entry, in addition to the +/- steppers — stepping from 60 to 480
     *  minutes in 5-minute increments takes 84 taps; this makes large adjustments practical. */
    private fun showDirectEntryDialog(tv: TextView) {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(tv.text)
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(editText)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_minutes_title))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = editText.text.toString().toLongOrNull()
                if (value != null) {
                    tv.text = TimerState.clampDuration(value).toString()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupListeners() {
        binding.btnFreeMinus.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if (!adjustTimer(binding.tvFreeMin, -5)) performClampedFeedback(v)
        }
        binding.btnFreePlus.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if (!adjustTimer(binding.tvFreeMin, 5)) performClampedFeedback(v)
        }
        binding.tvFreeMin.setOnClickListener { showDirectEntryDialog(binding.tvFreeMin) }

        binding.btnLockMinus.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if (!adjustTimer(binding.tvLockMin, -5)) performClampedFeedback(v)
        }
        binding.btnLockPlus.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if (!adjustTimer(binding.tvLockMin, 5)) performClampedFeedback(v)
        }
        binding.tvLockMin.setOnClickListener { showDirectEntryDialog(binding.tvLockMin) }

        binding.btnAllowMinus.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if (!adjustTimer(binding.tvAllowMin, -1)) performClampedFeedback(v)
        }
        binding.btnAllowPlus.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if (!adjustTimer(binding.tvAllowMin, 1)) performClampedFeedback(v)
        }
        binding.tvAllowMin.setOnClickListener { showDirectEntryDialog(binding.tvAllowMin) }

        // All three permission-card buttons open the full Setup Guide rather than jumping
        // straight to a raw system Settings screen — the guide explains *why* each permission
        // is needed before sending the user there (see SetupGuideActivity), instead of leaving
        // the "why" to the system's own, more technical dialog/settings text.
        binding.btnTimeLimitInfo.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.time_limit_info_title)
                .setMessage(R.string.time_limit_info_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.btnOverlay.setOnClickListener {
            startActivity(Intent(this, SetupGuideActivity::class.java))
        }

        binding.btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupGuideActivity::class.java))
        }

        binding.btnBattery.setOnClickListener {
            startActivity(Intent(this, SetupGuideActivity::class.java))
        }

        binding.btnApps.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            TransitionUtil.startWithFade(this, Intent(this, AppPickerActivity::class.java))
        }

        binding.btnStats.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            TransitionUtil.startWithFade(this, Intent(this, UsageStatsActivity::class.java))
        }

        binding.btnParental.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            TransitionUtil.startWithFade(this, Intent(this, ParentalControlActivity::class.java))
        }

        binding.toggleStrictness.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnGentle) {
                    TimerState.strictness = TimerState.Strictness.GENTLE
                    binding.tvModeDescription.text = getString(R.string.mode_desc_gentle)
                } else {
                    TimerState.strictness = TimerState.Strictness.NUCLEAR
                    binding.tvModeDescription.text = getString(R.string.mode_desc_nuclear)
                }
                TimerState.save(this)
            }
        }

        binding.switchStrict.setOnCheckedChangeListener(strictModeListener)

        binding.btnStart.setOnClickListener {
            if (TimerState.monitoredApps.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_select_apps), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // FIX M2: Validate and clamp duration inputs to safe range
            val freeRaw = binding.tvFreeMin.text.toString().toLongOrNull() ?: 60
            val lockRaw = binding.tvLockMin.text.toString().toLongOrNull() ?: 10
            val allowRaw = binding.tvAllowMin.text.toString().toLongOrNull() ?: 2
            val free = TimerState.clampDuration(freeRaw)
            val lock = TimerState.clampDuration(lockRaw)
            val allow = TimerState.clampDuration(allowRaw)

            // Update UI to reflect clamped values if they were changed
            if (free != freeRaw) binding.tvFreeMin.text = free.toString()
            if (lock != lockRaw) binding.tvLockMin.text = lock.toString()
            if (allow != allowRaw) binding.tvAllowMin.text = allow.toString()

            TimerState.freeDuration = free * 60
            TimerState.lockDuration = lock * 60
            TimerState.allowDuration = allow * 60
            TimerState.save(this)
            checkAndStartTracking()
        }

        binding.btnReset.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            TransitionUtil.startWithFade(this, Intent(this, PinActivity::class.java))
        }
    }

    /**
     * Strict Mode / Device Admin (FIX: previously the app could show the switch as ON while
     * the user had cancelled the system Device Admin dialog, and turning it OFF never actually
     * revoked admin — so the switch silently snapped back ON the next time the screen resumed.
     */
    private fun onStrictModeToggled(isChecked: Boolean) {
        if (isChecked) {
            if (isDeviceAdminActive()) {
                TimerState.strictMode = true
                TimerState.save(this)
            } else {
                // Explain what this actually does in-app, before bouncing to the system's own
                // Device Admin dialog — previously this jumped straight there with no in-app
                // context at all. Revert the switch if the user backs out here, since nothing
                // has been granted yet.
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.strict_mode_uninstall))
                    .setMessage(getString(R.string.admin_description))
                    .setPositiveButton(getString(R.string.setup_step_open_settings)) { _, _ ->
                        // Don't persist strictMode=true yet — wait for the user to actually
                        // grant admin in the system dialog. onResume() reconciles the outcome.
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_description))
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> syncStrictModeSwitch(false) }
                    .setOnCancelListener { syncStrictModeSwitch(false) }
                    .show()
            }
        } else {
            if (isDeviceAdminActive()) {
                try {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    dpm.removeActiveAdmin(ComponentName(this, AdminReceiver::class.java))
                } catch (e: SecurityException) {
                    Log.w(TAG, "Unable to remove device admin", e)
                }
            }
            val stillActive = isDeviceAdminActive()
            TimerState.strictMode = stillActive
            TimerState.save(this)
            if (stillActive) {
                // The device restricted self-removal — don't lie to the user; reflect reality.
                Toast.makeText(this, getString(R.string.toast_admin_removal_failed), Toast.LENGTH_LONG).show()
                syncStrictModeSwitch(true)
            }
        }
    }

    /** Sets the switch's checked state without re-triggering strictModeListener. */
    private fun syncStrictModeSwitch(checked: Boolean) {
        binding.switchStrict.setOnCheckedChangeListener(null)
        binding.switchStrict.isChecked = checked
        binding.switchStrict.setOnCheckedChangeListener(strictModeListener)
    }


    private fun checkAndStartTracking() {
        when {
            !Settings.canDrawOverlays(this) ->
                Toast.makeText(this, getString(R.string.toast_overlay_required), Toast.LENGTH_LONG).show()
            !AccessibilityUtils.isBlockerServiceEnabled(this) ->
                Toast.makeText(this, getString(R.string.toast_accessibility_required), Toast.LENGTH_LONG).show()
            else -> {
                TimerState.accessibilityHealthy = true
                val i = Intent(this, TimerService::class.java).apply { action = "START" }
                startForegroundService(i)
                updateUI()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TimerState.load(applicationContext)

        // FIX C5: Send explicit "RESUME" action so TimerService.onStartCommand
        // knows this is a reconnect, not a fresh start. Without an action, the
        // old code fell through to startForeground() which could time out.
        if (TimerState.isRunning()) {
            val i = Intent(this, TimerService::class.java).apply { action = "RESUME" }
            startForegroundService(i)
        }

        // FIX H3: Always sync switch state with actual device admin status.
        val adminActive = isDeviceAdminActive()
        syncStrictModeSwitch(adminActive)
        if (TimerState.strictMode != adminActive) {
            TimerState.strictMode = adminActive
            TimerState.save(this)
        }

        if (AccessibilityUtils.isBlockerServiceEnabled(this)) {
            TimerState.accessibilityHealthy = true
        }

        updateUI()
    }

    private fun updateUI() {
        TimerState.load(applicationContext)

        val accessOk = AccessibilityUtils.isBlockerServiceEnabled(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        // Notifications aren't gated by their own button in this card (Android 13+ only, and
        // not required for blocking to function) — btnSetup doubles as the generic "something
        // still needs attention" entry point into the full guide when it's the only thing left.
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        binding.cardPermissions.visibility = if (accessOk && overlayOk && batteryOk && notifOk) View.GONE else View.VISIBLE
        binding.btnSetup.visibility   = if (accessOk && notifOk) View.GONE else View.VISIBLE
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
                binding.btnFreeMinus.isEnabled  = true
                binding.btnFreePlus.isEnabled   = true
                binding.btnLockMinus.isEnabled  = true
                binding.btnLockPlus.isEnabled   = true
                binding.btnAllowMinus.isEnabled = true
                binding.btnAllowPlus.isEnabled  = true
                binding.tvFreeMin.isEnabled  = true
                binding.tvLockMin.isEnabled  = true
                binding.tvAllowMin.isEnabled = true
                binding.btnGentle.isEnabled  = true
                binding.btnNuclear.isEnabled = true
            }
            else -> {
                binding.tvSub.text = when {
                    !accessOk -> getString(R.string.accessibility_disabled_warning)
                    phase == TimerState.Phase.FREE    -> getString(R.string.enjoy_freely)
                    phase == TimerState.Phase.LOCKED  -> getString(R.string.locked_put_phone_down)
                    phase == TimerState.Phase.ALLOWED -> getString(R.string.quick_window)
                    else -> ""
                }
                binding.btnStart.visibility  = View.GONE
                binding.btnReset.visibility  = View.VISIBLE
                binding.btnFreeMinus.isEnabled  = false
                binding.btnFreePlus.isEnabled   = false
                binding.btnLockMinus.isEnabled  = false
                binding.btnLockPlus.isEnabled   = false
                binding.btnAllowMinus.isEnabled = false
                binding.btnAllowPlus.isEnabled  = false
                binding.tvFreeMin.isEnabled  = false
                binding.tvLockMin.isEnabled  = false
                binding.tvAllowMin.isEnabled = false
                binding.btnGentle.isEnabled  = false
                binding.btnNuclear.isEnabled = false
            }
        }
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
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(this, getString(R.string.toast_notifications_denied), Toast.LENGTH_LONG).show()
            }
        }
    }
}
