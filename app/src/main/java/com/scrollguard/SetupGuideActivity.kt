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
        private const val KEY_OEM_BACKGROUND_ACKNOWLEDGED = "oem_background_acknowledged"

        /**
         * OEMs with a documented history of aggressively killing background apps/services via
         * their own extra battery-management layer, on top of (not instead of) Android's own
         * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — see the DontKillMyApp project and this app's own
         * README Reliability Notes. Each carries the best-effort direct-launch component for its
         * OEM-specific settings screen; Android exposes no public API to read any of these
         * settings' current state, so — same as the original Xiaomi-only version of this card —
         * this can only ever be an acknowledge-once nudge, never a verified checkmark.
         *
         * Motorola is deliberately NOT included: it has no comparably well-documented separate
         * background-management screen beyond stock Android's own battery optimization (already
         * covered by the generic battery card above) — inventing a component that may not exist
         * would violate this app's own "don't claim more than we can verify" rule more than it
         * would help.
         */
        private enum class BackgroundManagementOem(
            val manufacturers: Set<String>,
            val componentPackage: String,
            val componentClass: String,
            val titleRes: Int,
            val whyRes: Int,
            val shortRes: Int,
            val acknowledgedRes: Int,
        ) {
            XIAOMI(
                setOf("xiaomi", "redmi", "poco"),
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity",
                R.string.setup_step_xiaomi_autostart_title, R.string.setup_step_xiaomi_autostart_why,
                R.string.setup_step_xiaomi_autostart_short, R.string.setup_step_xiaomi_autostart_acknowledged,
            ),
            SAMSUNG(
                setOf("samsung"),
                "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity",
                R.string.setup_step_samsung_battery_title, R.string.setup_step_samsung_battery_why,
                R.string.setup_step_samsung_battery_short, R.string.setup_step_samsung_battery_acknowledged,
            ),
            ONEPLUS(
                setOf("oneplus"),
                "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                R.string.setup_step_oneplus_battery_title, R.string.setup_step_oneplus_battery_why,
                R.string.setup_step_oneplus_battery_short, R.string.setup_step_oneplus_battery_acknowledged,
            ),
        }

        private fun detectBackgroundManagementOem(): BackgroundManagementOem? {
            val manufacturer = Build.MANUFACTURER.lowercase()
            return BackgroundManagementOem.entries.find { manufacturer in it.manufacturers }
        }

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
        applyEdgeToEdge(binding.root)

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

        // Both the primary action button AND the (i) info icon show the same disclosure dialog
        // before routing to system settings. Previously only the info icon did — the primary
        // button (the one most users actually tap) went straight to Settings with no in-app
        // disclosure at all, which doesn't satisfy an affirmative-consent requirement that's
        // supposed to sit in the way of granting the permission, not be an easy-to-skip aside.
        binding.btnAccessibilityAction.setOnClickListener { showAccessibilityDisclosure() }
        binding.btnAccessibilityInfo.setOnClickListener { showAccessibilityDisclosure() }

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

        val oem = detectBackgroundManagementOem()
        binding.cardXiaomiAutostart.visibility = if (oem != null) View.VISIBLE else View.GONE
        if (oem != null) {
            binding.tvXiaomiAutostartTitle.setText(oem.titleRes)
            binding.btnXiaomiAutostartAction.setOnClickListener { openOemBackgroundSettings(oem) }
            binding.btnXiaomiAutostartInfo.setOnClickListener {
                showInfoDialog(oem.titleRes, oem.whyRes) { openOemBackgroundSettings(oem) }
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
        if (AccessibilityUtils.isProtectionActive(this)) {
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

    /**
     * The disclosure body is chosen from the current [AccessibilityUtils.ProtectionState] rather
     * than always showing the same static text — a service that was enabled and stopped needs
     * "turn it off and back on" guidance, not the same first-time explanation shown to someone
     * who's never touched the setting. See the accessibility investigation notes for why Android
     * exposes no public API to tell "never enabled" apart from "blocked by Restricted Settings" —
     * both look identical, so [AccessibilityUtils.ProtectionState.DISABLED_MAY_BE_RESTRICTED]
     * uses install-source as a best-effort, non-definitive proxy and the copy reflects that.
     */
    private fun showAccessibilityDisclosure() {
        val bodyRes = when (AccessibilityUtils.getProtectionState(this)) {
            AccessibilityUtils.ProtectionState.ACTIVE,
            AccessibilityUtils.ProtectionState.DISABLED -> R.string.setup_step_accessibility_why
            AccessibilityUtils.ProtectionState.DISABLED_MAY_BE_RESTRICTED ->
                R.string.setup_step_accessibility_why_may_be_restricted
            AccessibilityUtils.ProtectionState.ENABLED_BUT_NOT_RUNNING ->
                R.string.setup_step_accessibility_why_stopped
        }
        showInfoDialog(R.string.setup_step_accessibility_title, bodyRes) {
            openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /**
     * MIUI/HyperOS's Autostart manager has no public Android API or documented Settings action —
     * this component name is the same undocumented-but-widely-relied-on one many third-party
     * apps use, and it can legitimately not exist on a given HyperOS version. Falling back to
     * this app's own App Info screen (always real) rather than failing silently or crashing:
     * from there the user can generally still find Autostart under Battery saver / permissions,
     * even if this direct shortcut doesn't land exactly on it.
     */
    private fun openOemBackgroundSettings(oem: BackgroundManagementOem) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OEM_BACKGROUND_ACKNOWLEDGED, true).apply()
        val direct = Intent().apply {
            component = android.content.ComponentName(oem.componentPackage, oem.componentClass)
        }
        try {
            startActivity(direct)
        } catch (e: Exception) {
            // Same fallback as before generalizing this: the exact component is undocumented and
            // can legitimately not exist on a given OEM software version — the app's own App Info
            // screen is always real, and from there the user can generally still find the
            // equivalent setting under Battery/permissions even if this shortcut doesn't land on
            // it directly.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
        refreshStatus()
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

        // The checkmark below must never appear unless the service is genuinely enabled AND
        // currently connected — a system setting reading "on" is not sufficient proof (see
        // AccessibilityUtils.ProtectionState doc). The one-line hint is state-specific so a user
        // stuck on Restricted Settings, or whose previously-working service has stopped, isn't
        // shown the same generic "Action needed" as someone who's simply never opened Settings.
        val protectionState = AccessibilityUtils.getProtectionState(this)
        val accessibilityActive = protectionState == AccessibilityUtils.ProtectionState.ACTIVE
        val accessibilityHint = when (protectionState) {
            AccessibilityUtils.ProtectionState.ACTIVE -> null
            AccessibilityUtils.ProtectionState.DISABLED -> getString(R.string.setup_step_accessibility_short)
            AccessibilityUtils.ProtectionState.DISABLED_MAY_BE_RESTRICTED ->
                getString(R.string.setup_step_accessibility_short_may_be_restricted)
            AccessibilityUtils.ProtectionState.ENABLED_BUT_NOT_RUNNING ->
                getString(R.string.setup_step_accessibility_short_stopped)
        }
        setRowState(
            binding.tvAccessibilityCheck, binding.tvAccessibilityStatus, binding.btnAccessibilityAction, accessibilityActive,
            actionNeededText = accessibilityHint
        )

        val overlayOk = Settings.canDrawOverlays(this)
        setRowState(binding.tvOverlayCheck, binding.tvOverlayStatus, binding.btnOverlayAction, overlayOk)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        setRowState(binding.tvBatteryCheck, binding.tvBatteryStatus, binding.btnBatteryAction, batteryOk)

        detectBackgroundManagementOem()?.let { oem ->
            // Android exposes no API to read any of these OEM-private settings' current state —
            // this can never be a real ✓, only "the user has been shown where to check." Don't
            // claim more than that (see Issue #5's "don't silently claim protection is active
            // when it isn't").
            val acknowledged = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OEM_BACKGROUND_ACKNOWLEDGED, false)
            binding.tvXiaomiAutostartStatus.text = getString(
                if (acknowledged) oem.acknowledgedRes else oem.shortRes
            )
        }

        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        setRowState(binding.tvNotificationsCheck, binding.tvNotificationsStatus, binding.btnNotificationsAction, notifOk)

        binding.tvAllDone.visibility =
            if (appsChosen && accessibilityActive && overlayOk && batteryOk && notifOk) View.VISIBLE else View.GONE
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
