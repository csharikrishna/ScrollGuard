package com.scrollguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.scrollguard.databinding.ActivitySetupGuideBinding

/**
 * A first-time (and always-reachable) walkthrough of every permission ScrollGuard needs, shown
 * before the user is ever asked to grant anything blind. Each row states what the permission is
 * for, in plain language, before sending the user to system Settings — and re-checks the real
 * system state on every resume, rather than assuming a return from Settings means success.
 */
class SetupGuideActivity : AppCompatActivity() {

    companion object {
        private const val NOTIF_PERMISSION_REQUEST_CODE = 201
        private const val PREFS = "sg_setup"
        private const val KEY_HAS_SEEN_GUIDE = "has_seen_setup_guide"
        private const val KEY_REQUESTED_NOTIF_PERMISSION = "requested_notif_permission"

        /** Whether the guide has ever been shown — used by MainActivity to decide whether to
         *  auto-launch it, so a returning user is never forced back through it repeatedly. */
        fun hasSeenGuide(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HAS_SEEN_GUIDE, false)

        private fun markGuideSeen(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HAS_SEEN_GUIDE, true).apply()
        }
    }

    private lateinit var binding: ActivitySetupGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardNotifications.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE

        binding.btnAppsAction.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        binding.btnAppsInfo.setOnClickListener {
            showInfoDialog(R.string.setup_step_apps_title, R.string.setup_step_apps_why, R.string.choose_apps) {
                startActivity(Intent(this, AppPickerActivity::class.java))
            }
        }

        binding.btnAccessibilityAction.setOnClickListener { openAccessibilitySettings() }
        binding.btnAccessibilityInfo.setOnClickListener {
            showInfoDialog(R.string.setup_step_accessibility_title, R.string.setup_step_accessibility_why) {
                openAccessibilitySettings()
            }
        }

        binding.btnOverlayAction.setOnClickListener { openOverlaySettings() }
        binding.btnOverlayInfo.setOnClickListener {
            showInfoDialog(R.string.setup_step_overlay_title, R.string.setup_step_overlay_why) {
                openOverlaySettings()
            }
        }

        binding.btnBatteryAction.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.btnBatteryInfo.setOnClickListener {
            showInfoDialog(R.string.setup_step_battery_title, R.string.setup_step_battery_why) {
                requestIgnoreBatteryOptimizations()
            }
        }

        binding.btnNotificationsAction.setOnClickListener { requestNotificationPermission() }
        binding.btnNotificationsInfo.setOnClickListener {
            showInfoDialog(R.string.setup_step_notifications_title, R.string.setup_step_notifications_why) {
                requestNotificationPermission()
            }
        }

        binding.btnContinue.setOnClickListener { confirmContinue() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        // Marked here, not in onCreate(): marking it as soon as the screen is merely created
        // means a user whose process dies (or who swipes the app away) before ever interacting
        // with it would never see this walkthrough auto-launch again on their next real open.
        // onPause() only fires once the user has actually been looking at the screen.
        markGuideSeen(this)
    }

    /**
     * Accessibility is the one hard dependency — without it ScrollGuard cannot detect app
     * launches at all, so blocking silently never functions. Continuing with every other
     * permission fine but accessibility missing previously exited straight to the main app with
     * no warning that nothing would actually work yet.
     */
    private fun confirmContinue() {
        if (AccessibilityUtils.isBlockerServiceEnabled(this)) {
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.setup_step_accessibility_title)
                .setMessage(R.string.setup_continue_without_accessibility_warning)
                .setPositiveButton(R.string.setup_continue_anyway) { _, _ -> finish() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        val alreadyRequestedBefore = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUESTED_NOTIF_PERMISSION, false)
        val canShowSystemPrompt = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.POST_NOTIFICATIONS
        )
        if (alreadyRequestedBefore && !canShowSystemPrompt) {
            // Denied at least once already, and Android will no longer show its own prompt —
            // this is the permanently-denied case. The row was previously stuck forever on
            // "Action needed" with a button that silently did nothing on every further tap.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            return
        }

        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REQUESTED_NOTIF_PERMISSION, true).apply()
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_PERMISSION_REQUEST_CODE) refreshStatus()
    }

    private fun showInfoDialog(
        titleRes: Int,
        messageRes: Int,
        actionTextRes: Int = R.string.setup_step_open_settings,
        onProceed: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(actionTextRes) { _, _ -> onProceed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshStatus() {
        // This may be the very first screen the user ever sees — TimerState's in-memory
        // defaults haven't necessarily been loaded from SharedPreferences yet.
        TimerState.load(this)

        val appsChosen = TimerState.monitoredApps.isNotEmpty()
        setRowState(
            binding.tvAppsCheck, binding.tvAppsStatus, binding.btnAppsAction, appsChosen,
            doneText = getString(R.string.setup_step_done)
        )

        val accessibilityOk = AccessibilityUtils.isBlockerServiceEnabled(this)
        setRowState(
            binding.tvAccessibilityCheck, binding.tvAccessibilityStatus, binding.btnAccessibilityAction, accessibilityOk,
            // This is the step users most often get stuck on, and its most useful guidance (the
            // "Restricted Setting" workaround) previously lived entirely behind the (i) icon,
            // easy to miss — show a short, one-line hint by default instead of a bare
            // "Action needed", without duplicating the full explanation the icon still opens.
            actionNeededText = getString(R.string.setup_step_accessibility_short)
        )

        val overlayOk = Settings.canDrawOverlays(this)
        setRowState(binding.tvOverlayCheck, binding.tvOverlayStatus, binding.btnOverlayAction, overlayOk)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        setRowState(binding.tvBatteryCheck, binding.tvBatteryStatus, binding.btnBatteryAction, batteryOk)

        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        setRowState(binding.tvNotificationsCheck, binding.tvNotificationsStatus, binding.btnNotificationsAction, notifOk)

        binding.tvAllDone.visibility =
            if (appsChosen && accessibilityOk && overlayOk && batteryOk && notifOk) View.VISIBLE else View.GONE
    }

    private fun setRowState(
        check: TextView,
        status: TextView,
        action: View,
        done: Boolean,
        doneText: String? = null,
        actionNeededText: String? = null
    ) {
        if (done) {
            check.text = "✓"
            check.setTextColor(ContextCompat.getColor(this, R.color.success))
            status.text = doneText ?: getString(R.string.setup_step_done)
            action.visibility = View.GONE
        } else {
            check.text = "○"
            check.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            status.text = actionNeededText ?: getString(R.string.setup_step_action_needed)
            action.visibility = View.VISIBLE
        }
    }
}
