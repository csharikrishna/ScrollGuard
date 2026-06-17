package com.scrollguard

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.scrollguard.databinding.ActivityPinBinding
import kotlin.random.Random

class PinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinBinding
    private var enteredPin = ""
    private var correctAnswer = 0
    // FIX #7: Track actual digit count instead of always using 4
    private var answerLength = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        generateTask()

        val keys = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6, binding.btn7,
            binding.btn8, binding.btn9
        )
        val nums = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")

        keys.forEachIndexed { i, btn ->
            btn.setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (enteredPin.length < answerLength) {
                    enteredPin += nums[i]
                    updateDots()
                    // FIX C3: Only check answer AFTER all digits are entered.
                    // Previously, toIntOrNull() was checked on every keystroke,
                    // causing premature failure if user typed a leading zero.
                    if (enteredPin.length == answerLength) {
                        if (enteredPin.toIntOrNull() == correctAnswer) {
                            onSuccess()
                        } else {
                            onFailure()
                        }
                    }
                }
            }
        }

        binding.btnDel.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.dropLast(1)
                updateDots()
            }
        }

        binding.btnCancel.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
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
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun onFailure() {
        Toast.makeText(this, getString(R.string.pin_failure), Toast.LENGTH_SHORT).show()
        enteredPin = ""
        updateDots()
        generateTask()
    }
}