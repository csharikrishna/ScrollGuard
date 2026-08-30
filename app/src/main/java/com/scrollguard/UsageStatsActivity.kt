package com.scrollguard

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
            setNoDataTextColor(ContextCompat.getColor(this@UsageStatsActivity, R.color.text_secondary))

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = ContextCompat.getColor(this@UsageStatsActivity, R.color.text_secondary)
                setDrawGridLines(false)
                granularity = 1f
                // FIX M7: Initial formatter is a no-op. The real formatter
                // is set in loadStats() once we have actual records.
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = ""
                }
            }

            axisLeft.apply {
                textColor = ContextCompat.getColor(this@UsageStatsActivity, R.color.text_secondary)
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(this@UsageStatsActivity, R.color.bg_surface_intense)
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

                // Calculate discipline score (50% base + cycles reward)
                val disciplineScore = if (records.isEmpty()) 70 else (70 + (totalCycles * 4)).coerceIn(50, 100)
                binding.tvDisciplineScore.text = "$disciplineScore%"

                if (records.isEmpty()) {
                    binding.chart.visibility = android.view.View.GONE
                    binding.layoutEmptyState.visibility = android.view.View.VISIBLE
                } else {
                    binding.chart.visibility = android.view.View.VISIBLE
                    binding.layoutEmptyState.visibility = android.view.View.GONE

                    val entries = records.mapIndexed { index, record ->
                        BarEntry(index.toFloat(), record.secondsSaved / 60f)
                    }

                    val dataSet = BarDataSet(entries, getString(R.string.chart_minutes_saved_label)).apply {
                        color = ContextCompat.getColor(this@UsageStatsActivity, R.color.primary)
                        setDrawValues(true)
                        valueTextColor = ContextCompat.getColor(this@UsageStatsActivity, R.color.text_secondary)
                        valueTextSize = 10f
                        highLightAlpha = 0
                    }

                    binding.chart.data = BarData(dataSet)
                    binding.chart.xAxis.valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            val index = value.toInt()
                            if (index < 0 || index >= records.size) return ""
                            val sdf = SimpleDateFormat("MM/dd", Locale.getDefault())
                            return sdf.format(Date(records[index].date))
                        }
                    }

                    binding.chart.animateY(1000)
                    binding.chart.invalidate()
                }

                // Load and render top blocked apps
                loadTopBlockedApps()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load usage stats", e)
                Snackbar.make(binding.root, getString(R.string.error_loading_stats), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.action_retry)) { loadStats() }
                    .show()
            }
        }
    }

    private fun loadTopBlockedApps() {
        lifecycleScope.launch {
            try {
                repository.getTopBlockedApps().collect { topApps ->
                    binding.layoutTopBlockedApps.removeAllViews()

                    if (topApps.isEmpty()) {
                        binding.layoutTopBlockedApps.addView(binding.tvNoBlocks)
                        binding.tvNoBlocks.visibility = android.view.View.VISIBLE
                    } else {
                        for (i in topApps.indices) {
                            val item = topApps[i]
                            val rank = "#${i + 1}"

                            val rowView = android.widget.LinearLayout(this@UsageStatsActivity).apply {
                                orientation = android.widget.LinearLayout.HORIZONTAL
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    if (i > 0) topMargin = (12 * resources.displayMetrics.density).toInt()
                                }
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }

                            val tvName = android.widget.TextView(this@UsageStatsActivity).apply {
                                text = "$rank  ${item.appName}"
                                setTextColor(ContextCompat.getColor(this@UsageStatsActivity, R.color.text_primary))
                                textSize = 15f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    0,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f
                                )
                            }

                            val tvCount = android.widget.TextView(this@UsageStatsActivity).apply {
                                text = getString(R.string.times_blocked_format, item.count)
                                setTextColor(ContextCompat.getColor(this@UsageStatsActivity, R.color.text_secondary))
                                textSize = 13f
                            }

                            rowView.addView(tvName)
                            rowView.addView(tvCount)
                            binding.layoutTopBlockedApps.addView(rowView)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load top blocked apps", e)
            }
        }
    }
}
