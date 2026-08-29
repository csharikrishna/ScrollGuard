package com.scrollguard

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.scrollguard.databinding.ActivityBlockBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        setupImmersiveMode()
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
            setupParentalLimitMode()
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
                        Toast.makeText(this, getString(R.string.stay_strong), Toast.LENGTH_SHORT).show()
                        dismissWithGrace()
                    }
                }
            }, GENTLE_DISMISS_DELAY_MS)
        }
    }

    private fun dismissWithGrace() {
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

        // Log block event for analytics
        blockedPackage?.let { pkg ->
            lifecycleScope.launch(Dispatchers.IO) {
                val appName = ParentalControlState.getRestriction(pkg)?.appName ?: pkg
                com.scrollguard.data.DataRepository.getInstance(applicationContext)
                    .logBlockEvent(pkg, appName, "PARENTAL_LIMIT")
            }
        }

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

        // Show parental guidance message
        binding.tvSubMessage.visibility = View.VISIBLE
        binding.tvSubMessage.text = getString(R.string.parental_ask_parent)
        binding.tvSubMessage.isClickable = false

        setupParentalTimeRequest()

        // Watch for parental config changes (parent grants more time).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TimerState.tickSignal.collect {
                    val pkg = blockedPackage ?: return@collect
                    if (!ParentalControlState.isAppQuotaExhausted(pkg)) {
                        finish() // Parent granted more time — dismiss block screen.
                    }
                }
            }
        }
    }

    private fun setupParentalTimeRequest() {
        val fid = ParentalControlState.familyId ?: return
        val pkg = blockedPackage ?: return
        val appName = ParentalControlState.getRestriction(pkg)?.appName ?: pkg

        binding.btnRequestTime.setOnClickListener {
            binding.btnRequestTime.isEnabled = false
            binding.tvOverrideStatus.visibility = View.VISIBLE
            binding.tvOverrideStatus.text = getString(R.string.request_sent_waiting)

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val reqRef = firestore.collection("families").document(fid)
                        .collection("requests").document()

                    reqRef.set(mapOf(
                        "id" to reqRef.id,
                        "packageName" to pkg,
                        "appName" to appName,
                        "minutes" to 10,
                        "status" to "PENDING",
                        "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    ))

                    // Listen for parent approval in real-time
                    timeRequestListener?.remove()
                    timeRequestListener = reqRef.addSnapshotListener { snapshot, _ ->
                        if (snapshot == null) return@addSnapshotListener
                        val status = snapshot.getString("status")
                        if (status == "APPROVED") {
                            Toast.makeText(this@BlockActivity, getString(R.string.request_approved), Toast.LENGTH_SHORT).show()
                            finish()
                        } else if (status == "DENIED") {
                            binding.tvOverrideStatus.text = getString(R.string.request_denied)
                            binding.btnRequestTime.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    binding.tvOverrideStatus.text = getString(R.string.error_request_send_failed)
                    binding.btnRequestTime.isEnabled = true
                }
            }
        }
    }

    // ── Common setup ────────────────────────────────────────────────────

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

