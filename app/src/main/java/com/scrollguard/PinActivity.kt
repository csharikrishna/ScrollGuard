package com.scrollguard

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scrollguard.databinding.ActivityPinBinding
import kotlin.random.Random

class PinActivity : AppCompatActivity() {

    companion object {
        /** After this many consecutive wrong answers, briefly lock the keypad. Bounded and
         *  always self-expiring — never a permanent lockout. */
        private const val MAX_ATTEMPTS_BEFORE_COOLDOWN = 3
        private const val COOLDOWN_MS = 5_000L
        /** Brief pause between the final digit and evaluating it, so a mistyped last digit can
         *  still be corrected with the delete key instead of instantly failing and generating a
         *  brand new problem — matches how OTP auto-submit UIs give a moment before committing. */
        private const val ANSWER_CHECK_DELAY_MS = 350L
    }

    private lateinit var binding: ActivityPinBinding
    private var enteredPin = ""
    private var correctAnswer = 0
    // FIX #7: Track actual digit count instead of always using 4
    private var answerLength = 3
    private var consecutiveWrongAnswers = 0
    private var cooldownTimer: CountDownTimer? = null
    private val checkHandler = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null

    private lateinit var digitButtons: List<android.widget.Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)

        generateTask()

        digitButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6, binding.btn7,
            binding.btn8, binding.btn9
        )
        val nums = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")

        digitButtons.forEachIndexed { i, btn ->
            btn.setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (enteredPin.length < answerLength) {
                    enteredPin += nums[i]
                    updateDots()
                    // FIX C3: Only check answer AFTER all digits are entered.
                    // Previously, toIntOrNull() was checked on every keystroke,
                    // causing premature failure if user typed a leading zero.
                    if (enteredPin.length == answerLength) {
                        // Give a brief window before actually evaluating — a fat-fingered final
                        // digit used to fail instantly with no way to fix just that one digit,
                        // forcing a whole new problem. Delete (below) cancels this if pressed
                        // in time.
                        pendingCheck?.let { checkHandler.removeCallbacks(it) }
                        val check = Runnable {
                            if (enteredPin.toIntOrNull() == correctAnswer) {
                                onSuccess()
                            } else {
                                onFailure()
                            }
                        }
                        pendingCheck = check
                        checkHandler.postDelayed(check, ANSWER_CHECK_DELAY_MS)
                    }
                }
            }
        }

        binding.btnDel.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            pendingCheck?.let { checkHandler.removeCallbacks(it) }
            pendingCheck = null
            if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.dropLast(1)
                updateDots()
            }
        }

        binding.btnCancel.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            TransitionUtil.finishWithFade(this)
        }
    }

    override fun onDestroy() {
        cooldownTimer?.cancel()
        pendingCheck?.let { checkHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun generateTask() {
        val a = Random.nextInt(100, 500)
        val b = Random.nextInt(100, 500)
        correctAnswer = a + b
        // FIX #7: Compute slot count from actual answer length (always 3 for 200–998)
        answerLength = correctAnswer.toString().length
        binding.tvTask.text = getString(R.string.pin_solve_format, a, b)
        enteredPin = ""
        updateDots()
    }

    private fun updateDots() {
        // FIX #7: Use answerLength slots instead of hardcoded 4
        binding.tvDots.text = enteredPin
            .padEnd(answerLength, '○')
            .chunked(1)
            .joinToString(" ")
    }

    private fun onSuccess() {
        TimerState.load(applicationContext)
        val stopIntent = Intent(this, TimerService::class.java).apply { action = "RESET" }
        startService(stopIntent)
        TimerState.reset(applicationContext)
        Toast.makeText(this, getString(R.string.pin_success), Toast.LENGTH_SHORT).show()
        TransitionUtil.finishWithFade(this)
    }

    private fun onFailure() {
        consecutiveWrongAnswers++
        Toast.makeText(this, getString(R.string.pin_failure), Toast.LENGTH_SHORT).show()
        enteredPin = ""
        updateDots()
        generateTask()

        if (consecutiveWrongAnswers >= MAX_ATTEMPTS_BEFORE_COOLDOWN) {
            startCooldown()
        }
    }

    /** A short, always-expiring cooldown after repeated wrong answers — enough friction to
     *  discourage mindless retrying, never long or permanent enough to lock anyone out. */
    private fun startCooldown() {
        consecutiveWrongAnswers = 0
        setKeypadEnabled(false)
        binding.tvCooldown.visibility = android.view.View.VISIBLE
        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer(COOLDOWN_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000) + 1
                binding.tvCooldown.text = getString(R.string.pin_cooldown_format, secs)
            }
            override fun onFinish() {
                binding.tvCooldown.visibility = android.view.View.GONE
                setKeypadEnabled(true)
            }
        }.start()
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        digitButtons.forEach { it.isEnabled = enabled }
        binding.btnDel.isEnabled = enabled
    }
}
