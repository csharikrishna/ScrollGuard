package com.scrollguard

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.scrollguard.data.AppEntry
import com.scrollguard.data.AppPickerItem
import com.scrollguard.data.DataRepository
import com.scrollguard.databinding.ActivityAppPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var repository: DataRepository
    private var adapter: AppPickerAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = DataRepository.getInstance(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvApps.layoutManager = LinearLayoutManager(this)

        setupSearch()
        loadApps()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter?.filter?.filter(s)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            // FIX #6: Load TimerState BEFORE reading monitoredApps.
            // Without this, after process death monitoredApps is empty in memory
            // and all apps appear unchecked even if previously selected.
            TimerState.load(applicationContext)

            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val monitored = TimerState.monitoredApps

            val usageStatsMap = if (hasUsageStatsPermission()) {
                val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val start = end - (1000 * 60 * 60 * 24) // 24 hours
                usm.queryAndAggregateUsageStats(start, end)
                    .mapValues { it.value.totalTimeInForeground }
            } else {
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, "Grant Usage Access to see daily time spent per app", Snackbar.LENGTH_LONG)
                        .setAction("GRANT") {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }.show()
                }
                emptyMap<String, Long>()
            }

            val items = packages
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName == "com.instagram.android" }
                .map { appInfo ->
                    AppPickerItem(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo),
                        isMonitored = monitored.contains(appInfo.packageName),
                        usageTimeMillis = usageStatsMap[appInfo.packageName] ?: 0L
                    )
                }
                .sortedWith(compareByDescending<AppPickerItem> { it.usageTimeMillis }.thenBy { it.appName.lowercase() })

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                adapter = AppPickerAdapter(items) { item, isChecked ->
                    if (isChecked) {
                        TimerState.monitoredApps.add(item.packageName)
                        lifecycleScope.launch { repository.addApp(AppEntry(item.packageName, item.appName)) }
                    } else {
                        TimerState.monitoredApps.remove(item.packageName)
                        lifecycleScope.launch { repository.removeApp(item.packageName) }
                    }
                    TimerState.save(this@AppPickerActivity)
                }
                binding.rvApps.adapter = adapter

                val currentSearch = binding.etSearch.text
                if (!currentSearch.isNullOrEmpty()) {
                    adapter?.filter?.filter(currentSearch)
                }
            }
        }
    }
}