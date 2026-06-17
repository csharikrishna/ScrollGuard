package com.scrollguard

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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

            val items = packages
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName == "com.instagram.android" }
                .map { appInfo ->
                    AppPickerItem(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo),
                        isMonitored = monitored.contains(appInfo.packageName)
                    )
                }
                .sortedBy { it.appName.lowercase() }

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