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
import com.google.android.material.snackbar.Snackbar
import com.scrollguard.data.DataRepository
import com.scrollguard.databinding.ActivityUsageStatsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        binding.toolbar.setNavigationOnClickListener { TransitionUtil.finishWithFade(this) }

        // Set before the async load below so there's never a frame showing raw "0h 0m"/"0"
        // hardcoded text — the real data comes from a Room Flow that hasn't emitted yet.
        binding.tvTotalSaved.text = getString(R.string.time_saved_format, 0, 0)
        binding.tvTotalCycles.text = "0"

        setupChart()
        loadStats()
    }

    private fun setupChart() {
        binding.chart.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            legend.isEnabled = false
            setNoDataText(getString(R.string.no_data_yet))
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
                binding.tvTotalSaved.text = getString(R.string.time_saved_format, h, m)
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

                val dataSet = BarDataSet(entries, getString(R.string.chart_minutes_saved_label)).apply {
                    color = Color.parseColor("#FF4081")
                    setDrawValues(true)
                    valueTextColor = Color.WHITE
                    valueTextSize = 10f
                    highLightAlpha = 0
                }

                binding.chart.data = BarData(dataSet)
                binding.chart.xAxis.valueFormatter = object : ValueFormatter() {
                    // A fresh SimpleDateFormat per call (rather than a cached field) avoids both
                    // the "stale locale if the user changes it mid-session" lint warning and
                    // SimpleDateFormat's well-known lack of thread safety.
                    override fun getFormattedValue(value: Float): String {
                        val index = value.toInt()
                        if (index < 0 || index >= records.size) return ""
                        val sdf = SimpleDateFormat("MM/dd", Locale.getDefault())
                        return sdf.format(Date(records[index].date))
                    }
                }

                binding.chart.animateY(1000)
                binding.chart.invalidate()
            } catch (e: Exception) {
                // Don't fail silently: a chart that's merely empty looks identical to one that
                // failed to load unless we say otherwise.
                Log.e(TAG, "Failed to load usage stats", e)
                Snackbar.make(binding.root, getString(R.string.error_loading_stats), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.action_retry)) { loadStats() }
                    .show()
            }
        }
    }
}
