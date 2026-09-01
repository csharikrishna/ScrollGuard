package com.scrollguard

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.scrollguard.databinding.ActivityBlockBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class BlockActivity : AppCompatActivity() {

    companion object {
        /** Delay before showing the dismiss action in GENTLE mode (ms). */
        private const val GENTLE_DISMISS_DELAY_MS = 15_000L
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
        const val EXTRA_BLOCK_MODE = "extra_block_mode"
    }

    private lateinit var binding: ActivityBlockBinding
    private val handler = Handler(Looper.getMainLooper())

    /** The package that triggered this block screen. Grace, if dismissed, is granted only
     *  to this package — not to every monitored app — matching the per-package architecture
     *  of TimerState.isAppBlocked(). Updated on both onCreate and onNewIntent since this
     *  Activity is singleTask and can be re-delivered an Intent instead of recreated. */
    private var blockedPackage: String? = null

    /** The block mode: FOCUS_TIMER (existing) or PARENTAL_LIMIT (new). */
    private var blockMode: String = BlockerAccessibilityService.BLOCK_MODE_FOCUS_TIMER
    private var timeRequestListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(binding.root)
        setupShowOverLockScreen()
        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        blockMode = intent.getStringExtra(EXTRA_BLOCK_MODE)
            ?: BlockerAccessibilityService.BLOCK_MODE_FOCUS_TIMER

        // FIX H2: In GENTLE mode, back press dismisses (grants grace) same as the tap action.
        // In NUCLEAR mode and PARENTAL_LIMIT mode, back press does nothing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (blockMode == BlockerAccessibilityService.BLOCK_MODE_FOCUS_TIMER &&
                    TimerState.strictness == TimerState.Strictness.GENTLE) {
                    dismissWithGrace()
                }
                // PARENTAL_LIMIT: back press does nothing — only parent can grant more time.
            }
        })

        if (blockMode == BlockerAccessibilityService.BLOCK_MODE_PARENTAL_LIMIT) {
            setupParentalLimitMode()
        } else {
            setupFocusTimerMode()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)?.let { blockedPackage = it }
        blockMode = intent.getStringExtra(EXTRA_BLOCK_MODE)
            ?: BlockerAccessibilityService.BLOCK_MODE_FOCUS_TIMER

        if (blockMode == BlockerAccessibilityService.BLOCK_MODE_PARENTAL_LIMIT) {
            // Not setupParentalLimitMode() again — onNewIntent means the accessibility service
            // re-affirmed an already-showing block (it fires this on nearly every tick/event
            // while the exhausted app stays foreground), not a new block "session". Calling the
            // full setup here used to re-log a duplicate BlockEvent and re-register the time
            // request UI/listener every single time.
            refreshParentalLimitUI()
        } else {
            TimerState.load(applicationContext)
            updateFocusTimerUI()
        }
    }

    // ── Focus Timer Mode (existing behavior) ────────────────────────────

    private fun setupFocusTimerMode() {
        binding.btnEmergencyPass.visibility = View.VISIBLE
        binding.btnRequestTime.visibility = View.GONE
        binding.tvOverrideStatus.visibility = View.GONE

        TimerState.load(applicationContext)
        updateFocusTimerUI()
        setupGentleMode()
        setupPersonalEmergencyPass()

        // Log block event for analytics
        blockedPackage?.let { pkg ->
            lifecycleScope.launch(Dispatchers.IO) {
                val appName = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
                com.scrollguard.data.DataRepository.getInstance(applicationContext)
                    .logBlockEvent(pkg, appName, "FOCUS_TIMER")
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TimerState.tickSignal.collect {
                    TimerState.load(applicationContext)
                    updateFocusTimerUI()
                    val pkg = blockedPackage
                    val stillBlocked = TimerState.phase == TimerState.Phase.LOCKED &&
                        (pkg == null || TimerState.isAppBlocked(pkg))
                    if (!stillBlocked) finish()
                }
            }
        }
    }

    private fun setupPersonalEmergencyPass() {
        val prefs = getSharedPreferences("sg_emergency", MODE_PRIVATE)
        val today = java.time.LocalDate.now().toEpochDay()
        val lastUsedDay = prefs.getLong("last_emergency_day", 0L)

        if (lastUsedDay == today) {
            binding.btnEmergencyPass.isEnabled = false
            binding.btnEmergencyPass.alpha = 0.5f
            binding.btnEmergencyPass.text = getString(R.string.emergency_pass_used_today)
        } else {
            binding.btnEmergencyPass.isEnabled = true
            binding.btnEmergencyPass.alpha = 1.0f
            binding.btnEmergencyPass.text = getString(R.string.emergency_pass_btn)
            binding.btnEmergencyPass.setOnClickListener {
                prefs.edit().putLong("last_emergency_day", today).apply()
                Toast.makeText(this, getString(R.string.emergency_pass_granted), Toast.LENGTH_SHORT).show()
                blockedPackage?.let { pkg ->
                    // Grant 5 minutes of grace (300 seconds)
                    TimerState.grantGrace(pkg, 300_000L)
                }
                finish()
            }
        }
    }

    private fun updateFocusTimerUI() {
        val remaining = TimerState.getRemainingSeconds()
        binding.tvTimer.text = TimerState.fmtTime(remaining)

        binding.tvMessage.text = getString(R.string.focus_mode_active)
        binding.tvMessage.visibility = View.VISIBLE

        val totalDuration = when (TimerState.phase) {
            TimerState.Phase.LOCKED  -> TimerState.lockDuration.toFloat()
            TimerState.Phase.ALLOWED -> TimerState.allowDuration.toFloat()
            else                     -> TimerState.lockDuration.toFloat()
        }.coerceAtLeast(1f)

        val progress = ((remaining / totalDuration) * 100).toInt().coerceIn(0, 100)
        binding.progressTimer.progress = progress
    }

    /**
     * FIX H2 / grace redesign: GENTLE mode shows a dismiss affordance after a delay.
     * NUCLEAR: no dismiss option is ever shown.
     */
    private fun setupGentleMode() {
        if (TimerState.strictness == TimerState.Strictness.GENTLE) {
            binding.tvSubMessage.visibility = View.VISIBLE
            binding.tvSubMessage.text = getString(R.string.short_form_addictive)
            binding.tvSubMessage.isClickable = false

            handler.postDelayed({
                if (!isFinishing) {
                    binding.tvSubMessage.text = getString(R.string.gentle_dismiss)
                    binding.tvSubMessage.isClickable = true
                    binding.tvSubMessage.setOnClickListener {
                        dismissWithGrace()
                    }
                }
            }, GENTLE_DISMISS_DELAY_MS)
        }
    }

    private fun dismissWithGrace() {
        // Shown here (not at each call site) so every way of dismissing a GENTLE-mode block —
        // tapping the on-screen message or pressing back/swiping — gives the same feedback.
        // Back press previously called this directly and skipped the toast entirely, making the
        // screen just silently vanish instead.
        Toast.makeText(this, getString(R.string.stay_strong), Toast.LENGTH_SHORT).show()
        blockedPackage?.let { pkg -> TimerState.grantGrace(pkg) }
        finish()
    }

    // ── Parental Limit Mode ─────────────────────────────────────────────

    private fun setupParentalLimitMode() {
        binding.btnEmergencyPass.visibility = View.GONE
        binding.btnRequestTime.visibility = View.VISIBLE
        binding.tvOverrideStatus.visibility = View.GONE

        binding.tvMessage.text = getString(R.string.parental_limit_reached)
        binding.tvMessage.visibility = View.VISIBLE

        // Log block event for analytics. Only here, in the one-time setup — NOT repeated for
        // every onNewIntent re-affirmation of this same still-showing screen (see onNewIntent's
        // refreshParentalLimitUI), which is what previously flooded BlockEvent with duplicates
        // for a single continuous block "session."
        blockedPackage?.let { pkg ->
            lifecycleScope.launch(Dispatchers.IO) {
                val appName = ParentalControlState.getRestriction(pkg)?.appName ?: pkg
                com.scrollguard.data.DataRepository.getInstance(applicationContext)
                    .logBlockEvent(pkg, appName, "PARENTAL_LIMIT")
            }
        }

        updateParentalLimitTimerUI()

        // Show parental guidance message
        binding.tvSubMessage.visibility = View.VISIBLE
        binding.tvSubMessage.text = getString(R.string.parental_ask_parent)
        binding.tvSubMessage.isClickable = false

        setupParentalTimeRequest()
        startParentalUnblockWatcher()
    }

    /** Re-affirmation path for onNewIntent — same screen, not a new session, so it only refreshes
     *  the displayed consumed/allowance numbers without repeating the one-time setup above. */
    private fun refreshParentalLimitUI() {
        updateParentalLimitTimerUI()
    }

    private fun updateParentalLimitTimerUI() {
        // Show time spent today instead of countdown timer.
        val snap = blockedPackage?.let { ParentalControlState.getRestriction(it) }
        if (snap != null) {
            val consumedMinutes = snap.consumedSeconds / 60
            val allowanceMinutes = snap.allowanceSeconds / 60
            binding.tvTimer.text = getString(
                R.string.parental_time_spent_format,
                consumedMinutes,
                allowanceMinutes
            )
            binding.progressTimer.max = 100
            binding.progressTimer.progress = 0 // No time remaining
        } else {
            binding.tvTimer.text = getString(R.string.parental_limit_reached)
            binding.progressTimer.progress = 0
        }
    }

    /**
     * Watches for the parent granting more time, independent of TimerState.tickSignal — that
     * signal is only published while a *personal* Focus Timer session's TimerService is alive,
     * so a child under Parental Control alone (no personal session running) previously had no
     * live path to auto-dismiss this screen at all; only backgrounding and reopening the app
     * would re-check (via onResume) and let them back in. This polls local ParentalControlState
     * directly — cheap, no network of its own, since the live Firestore listener (or SyncWorker)
     * is what keeps that state current.
     */
    private fun startParentalUnblockWatcher() {
        val poll = object : Runnable {
            override fun run() {
                val pkg = blockedPackage
                if (pkg != null && !ParentalControlState.isAppQuotaExhausted(pkg)) {
                    finish()
                    return
                }
                handler.postDelayed(this, 1_000L)
            }
        }
        handler.postDelayed(poll, 1_000L)
    }

    /**
     * Handles the "ask for more time" flow. Recovers an already-pending request for this exact
     * package instead of always assuming there is none — without this, rotating the device (which
     * destroys/recreates this Activity; no configChanges is declared) reset the button back to
     * enabled, letting a child spam unlimited duplicate requests, AND silently dropped the live
     * listener for whichever request was already pending, so if the parent approved that original
     * request the screen would never know and never unblock.
     */
    private fun setupParentalTimeRequest() {
        val fid = ParentalControlState.familyId ?: return
        val pkg = blockedPackage ?: return
        val appName = ParentalControlState.getRestriction(pkg)?.appName ?: pkg
        val requestsRef = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("families").document(fid).collection("requests")

        binding.btnRequestTime.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val existing = try {
                requestsRef.whereEqualTo("type", "TIME")
                    .whereEqualTo("packageName", pkg)
                    .whereEqualTo("status", "PENDING")
                    .get().await().documents.firstOrNull()
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (existing != null) {
                    binding.tvOverrideStatus.visibility = View.VISIBLE
                    binding.tvOverrideStatus.text = getString(R.string.request_sent_waiting)
                    attachTimeRequestListener(existing.reference)
                } else {
                    binding.btnRequestTime.isEnabled = true
                }
            }
        }

        binding.btnRequestTime.setOnClickListener {
            binding.btnRequestTime.isEnabled = false
            binding.tvOverrideStatus.visibility = View.VISIBLE
            binding.tvOverrideStatus.text = getString(R.string.request_sent_waiting)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val reqRef = requestsRef.document()
                    reqRef.set(mapOf(
                        "id" to reqRef.id,
                        "type" to "TIME",
                        "packageName" to pkg,
                        "appName" to appName,
                        "minutes" to 10,
                        "status" to "PENDING",
                        "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )).await()
                    withContext(Dispatchers.Main) { attachTimeRequestListener(reqRef) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.tvOverrideStatus.text = getString(R.string.error_request_send_failed)
                        binding.btnRequestTime.isEnabled = true
                    }
                }
            }
        }
    }

    private fun attachTimeRequestListener(reqRef: com.google.firebase.firestore.DocumentReference) {
        timeRequestListener?.remove()
        timeRequestListener = reqRef.addSnapshotListener { snapshot, _ ->
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            when (snapshot.getString("status")) {
                "APPROVED" -> {
                    Toast.makeText(this@BlockActivity, getString(R.string.request_approved), Toast.LENGTH_SHORT).show()
                    // Resolved — delete rather than leaving it to accumulate forever (the parent
                    // dashboard only ever queries status == PENDING, so this was pure orphaned
                    // storage, not a functional bug, but it grows without bound otherwise).
                    snapshot.reference.delete()
                    finish()
                }
                "DENIED" -> {
                    binding.tvOverrideStatus.text = getString(R.string.request_denied)
                    binding.btnRequestTime.isEnabled = true
                    snapshot.reference.delete()
                }
            }
        }
    }

    // ── Common setup ────────────────────────────────────────────────────

    /** Ensures the block screen actually appears over the lock screen and wakes the display. */
    private fun setupShowOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (blockMode == BlockerAccessibilityService.BLOCK_MODE_PARENTAL_LIMIT) {
            val pkg = blockedPackage
            if (pkg != null && !ParentalControlState.isAppQuotaExhausted(pkg)) {
                finish()
            }
        } else {
            TimerState.load(applicationContext)
            updateFocusTimerUI()
            val pkg = blockedPackage
            val stillBlocked = TimerState.phase == TimerState.Phase.LOCKED &&
                (pkg == null || TimerState.isAppBlocked(pkg))
            if (!stillBlocked) finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        timeRequestListener?.remove()
        timeRequestListener = null
        super.onDestroy()
    }
}

