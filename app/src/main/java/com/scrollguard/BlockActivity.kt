package com.scrollguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.scrollguard.databinding.ActivityBlockBinding

class BlockActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BlockActivity"
        /** Delay before showing dismiss button in GENTLE mode (seconds) */
        private const val GENTLE_DISMISS_DELAY_MS = 15_000L
    }

    private lateinit var binding: ActivityBlockBinding
    private var isRelaunching = false
    private val handler = Handler(Looper.getMainLooper())

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            TimerState.load(applicationContext)
            updateUI()
            if (TimerState.phase != TimerState.Phase.LOCKED) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveMode()

        // FIX H2: In GENTLE mode, back press is allowed (same as dismiss).
        // In NUCLEAR mode, back press is blocked entirely.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (TimerState.strictness == TimerState.Strictness.GENTLE) {
                    finish()
                }
                // NUCLEAR: back press does nothing (intentionally blocked)
            }
        })

        TimerState.load(applicationContext)
        updateUI()
        setupGentleMode()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    /**
     * FIX H2: Implement differentiated behavior for GENTLE vs NUCLEAR mode.
     * GENTLE: After a delay, show a "Dismiss" text on the sub-message area.
     *         Tapping it closes the block screen (user can resume the app).
     * NUCLEAR: No dismiss option; aggressive relaunch on pause.
     */
    private fun setupGentleMode() {
        if (TimerState.strictness == TimerState.Strictness.GENTLE) {
            // Show a dismiss hint after a delay
            binding.tvSubMessage.visibility = View.VISIBLE
            binding.tvSubMessage.text = getString(R.string.short_form_addictive)
            binding.tvSubMessage.isClickable = false

            handler.postDelayed({
                if (!isFinishing) {
                    binding.tvSubMessage.text = getString(R.string.gentle_dismiss)
                    binding.tvSubMessage.setOnClickListener {
                        Toast.makeText(this, getString(R.string.stay_strong), Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }, GENTLE_DISMISS_DELAY_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        isRelaunching = false
        val filter = IntentFilter("com.scrollguard.TICK")
        ContextCompat.registerReceiver(
            this, tickReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        TimerState.load(applicationContext)
        updateUI()
        if (TimerState.phase != TimerState.Phase.LOCKED) finish()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
        maybeRelaunch()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeRelaunch()
    }

    private fun maybeRelaunch() {
        // FIX H2: In GENTLE mode, don't aggressively relaunch — allow the user to leave.
        if (TimerState.strictness == TimerState.Strictness.GENTLE) return

        if (isRelaunching) return
        TimerState.load(applicationContext)
        if (TimerState.phase == TimerState.Phase.LOCKED) {
            // FIX M4: Check overlay permission before relaunching.
            // If revoked at runtime, startActivity may fail on some OEMs.
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Overlay permission revoked — cannot relaunch block screen")
                return
            }
            isRelaunching = true
            val intent = Intent(this, BlockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            startActivity(intent)
        }
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