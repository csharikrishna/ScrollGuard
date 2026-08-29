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
import kotlinx.coroutines.launch

class BlockActivity : AppCompatActivity() {

    companion object {
        /** Delay before showing the dismiss action in GENTLE mode (ms). */
        private const val GENTLE_DISMISS_DELAY_MS = 15_000L
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
    }

    private lateinit var binding: ActivityBlockBinding
    private val handler = Handler(Looper.getMainLooper())

    /** The package that triggered this block screen. Grace, if dismissed, is granted only
     *  to this package — not to every monitored app — matching the per-package architecture
     *  of TimerState.isAppBlocked(). Updated on both onCreate and onNewIntent since this
     *  Activity is singleTask and can be re-delivered an Intent instead of recreated. */
    private var blockedPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()
        setupShowOverLockScreen()
        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)

        // FIX H2: In GENTLE mode, back press dismisses (grants grace) same as the tap action.
        // In NUCLEAR mode, back press does nothing (intentionally blocked) — this is the
        // strongest behavior the app can enforce without Device Owner / Lock Task privileges;
        // it cannot intercept Home or Recents (see README's Nuclear Mode Limitations section).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (TimerState.strictness == TimerState.Strictness.GENTLE) {
                    dismissWithGrace()
                }
            }
        })

        TimerState.load(applicationContext)
        updateUI()
        setupGentleMode()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TimerState.tickSignal.collect {
                    TimerState.load(applicationContext)
                    updateUI()
                    val pkg = blockedPackage
                    val stillBlocked = TimerState.phase == TimerState.Phase.LOCKED &&
                        (pkg == null || TimerState.isAppBlocked(pkg))
                    if (!stillBlocked) finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)?.let { blockedPackage = it }
        updateUI()
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

    /** Ensures the block screen actually appears over the lock screen and wakes the display
     *  on every supported API level (minSdk 26). The manifest-level android:showOnLockScreen /
     *  android:turnScreenOn attributes only take effect on API 27+, which lint correctly flags
     *  as an UnusedAttribute below minSdk — so API 26 is handled explicitly via the pre-27
     *  window flags instead of relying on an attribute the OS silently ignores there. */
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

    /**
     * FIX H2 / grace redesign: GENTLE mode shows a dismiss affordance after a delay.
     * Tapping it calls dismissWithGrace(), which grants the blocked package a temporary
     * bypass (TimerState.grantGrace) *before* finishing — without that, finishing this
     * Activity reveals the still-foreground blocked app, the accessibility service
     * immediately re-detects it as blocked, and relaunches this screen in a tight loop.
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

    override fun onResume() {
        super.onResume()
        TimerState.load(applicationContext)
        updateUI()
        val pkg = blockedPackage
        val stillBlocked = TimerState.phase == TimerState.Phase.LOCKED &&
            (pkg == null || TimerState.isAppBlocked(pkg))
        if (!stillBlocked) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun updateUI() {
        val remaining = TimerState.getRemainingSeconds()
        binding.tvTimer.text = TimerState.fmtTime(remaining)

        val totalDuration = when (TimerState.phase) {
            TimerState.Phase.LOCKED  -> TimerState.lockDuration.toFloat()
            TimerState.Phase.ALLOWED -> TimerState.allowDuration.toFloat()
            else                     -> TimerState.lockDuration.toFloat()
        }.coerceAtLeast(1f)

        val progress = ((remaining / totalDuration) * 100).toInt().coerceIn(0, 100)
        binding.progressTimer.progress = progress
    }
}
