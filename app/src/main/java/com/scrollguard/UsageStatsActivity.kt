package com.scrollguard

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.scrollguard.data.DataRepository
import com.scrollguard.databinding.ActivityUsageStatsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class UsageStatsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UsageStatsActivity"
    }

    private lateinit var binding: ActivityUsageStatsBinding
    private lateinit var repository: DataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsageStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = DataRepository.getInstance(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        setupChart()
        loadStats()
    }

    private fun setupChart() {
        binding.chart.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            legend.isEnabled = false
            setNoDataText("Start focusing to see your progress!")
            setNoDataTextColor(Color.parseColor("#B3FFFFFF"))

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#B3FFFFFF")
                setDrawGridLines(false)
                granularity = 1f
                // FIX M7: Initial formatter is a no-op. The real formatter
                // is set in loadStats() once we have actual records.
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = ""
                }
            }

            axisLeft.apply {
                textColor = Color.parseColor("#B3FFFFFF")
                setDrawGridLines(true)
                gridColor = Color.parseColor("#1AFFFFFF")
                axisMinimum = 0f
            }
            axisRight.isEnabled = false
        }
    }

    private fun loadStats() {
        lifecycleScope.launch {
            try {
                val records = repository.recentUsage.first().reversed()

                val totalSecs = records.sumOf { it.secondsSaved }
                val totalCycles = records.sumOf { it.cyclesCompleted }
                val h = totalSecs / 3600
                val m = (totalSecs % 3600) / 60
                binding.tvTotalSaved.text = "${h}h ${m}m"
                binding.tvTotalCycles.text = totalCycles.toString()

                if (records.isEmpty()) {
                    binding.chart.visibility = android.view.View.GONE
                    binding.layoutEmptyState.visibility = android.view.View.VISIBLE
                    return@launch
                } else {
                    binding.chart.visibility = android.view.View.VISIBLE
                    binding.layoutEmptyState.visibility = android.view.View.GONE
                }

                val entries = records.mapIndexed { index, record ->
                    BarEntry(index.toFloat(), record.secondsSaved / 60f)
                }

                val dataSet = BarDataSet(entries, "Minutes Saved").apply {
                    color = Color.parseColor("#FF4081")
                    setDrawValues(true)
                    valueTextColor = Color.WHITE
                    valueTextSize = 10f
                    highLightAlpha = 0
                }

                binding.chart.data = BarData(dataSet)
                binding.chart.xAxis.valueFormatter = object : ValueFormatter() {
                    val sdf = SimpleDateFormat("MM/dd", Locale.getDefault())
                    override fun getFormattedValue(value: Float): String {
                        val index = value.toInt()
                        return if (index >= 0 && index < records.size)
                            sdf.format(Date(records[index].date)) else ""
                    }
                }

                binding.chart.animateY(1000)
                binding.chart.invalidate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load usage stats", e)
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}