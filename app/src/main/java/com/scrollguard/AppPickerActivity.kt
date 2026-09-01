package com.scrollguard

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
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
        applyEdgeToEdge(binding.root)

        repository = DataRepository.getInstance(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        // The default ItemAnimator can leave a SwitchMaterial's thumb-position animation
        // mid-flight across a notifyDataSetChanged() (e.g. every search-filter keystroke),
        // rendering it checked/unchecked opposite to the just-bound, correct isChecked value —
        // the underlying monitored-state data is unaffected, but the switch visually lies to
        // the user about it. Disabling change animations for this list removes the animator
        // path that was carrying the stale state across rebinds.
        binding.rvApps.itemAnimator = null

        setupSearch()
    }

    override fun onResume() {
        super.onResume()
        // Re-checks Usage Access (and everything else) fresh on every resume, not just on
        // first open — otherwise a user who grants Usage Access via the snackbar's "GRANT"
        // action and returns from Settings saw stale (blank) per-app usage times until they
        // fully closed and reopened this screen.
        loadApps()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_app_picker, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_groups) {
            startActivity(Intent(this, AppGroupsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
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
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.layoutEmptyState.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            // FIX #6: Load TimerState BEFORE reading monitoredApps.
            // Without this, after process death monitoredApps is empty in memory
            // and all apps appear unchecked even if previously selected.
            TimerState.load(applicationContext)

            val pm = packageManager
            // Built directly from queryIntentActivities rather than
            // PackageManager#getInstalledApplications: this is exactly what the manifest's
            // <queries> block already declares visibility for (apps with a launcher
            // activity), it naturally matches "what the user could tap to open from their
            // home screen" with no FLAG_SYSTEM guessing or per-app special-casing, and it
            // doesn't trigger the Android 11+ package-visibility restriction that
            // getInstalledApplications is subject to — so there's nothing to suppress.
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seenPackages = HashSet<String>()
            val packages = pm.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                // Some apps expose more than one launcher activity; dedupe by package.
                .filter { it.packageName != packageName && seenPackages.add(it.packageName) }

            // Read once up front: TimerState.monitoredApps is a live, thread-safe set that
            // could be mutated concurrently from the UI thread while this list is built.
            val monitoredSnapshot = HashSet(TimerState.monitoredApps)

            val usageStatsMap = if (hasUsageStatsPermission()) {
                val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val start = end - (1000 * 60 * 60 * 24) // 24 hours
                usm.queryAndAggregateUsageStats(start, end)
                    .mapValues { it.value.totalTimeInForeground }
            } else {
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, getString(R.string.grant_usage_access_snackbar), Snackbar.LENGTH_LONG)
                        .setAction(getString(R.string.action_grant)) {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }.show()
                }
                emptyMap<String, Long>()
            }

            val items = packages
                .map { appInfo ->
                    AppPickerItem(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        icon = pm.getApplicationIcon(appInfo),
                        isMonitored = monitoredSnapshot.contains(appInfo.packageName),
                        usageTimeMillis = usageStatsMap[appInfo.packageName] ?: 0L
                    )
                }
                .sortedWith(compareByDescending<AppPickerItem> { it.usageTimeMillis }.thenBy { it.appName.lowercase() })

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                val newAdapter = AppPickerAdapter(items) { item, isChecked ->
                    if (isChecked) {
                        TimerState.monitoredApps.add(item.packageName)
                        lifecycleScope.launch { repository.addApp(AppEntry(item.packageName, item.appName)) }
                    } else {
                        TimerState.monitoredApps.remove(item.packageName)
                        lifecycleScope.launch { repository.removeApp(item.packageName) }
                    }
                    TimerState.save(this@AppPickerActivity)
                }
                newAdapter.onVisibleCountChanged = { count ->
                    binding.layoutEmptyState.visibility = if (count == 0) View.VISIBLE else View.GONE
                }
                adapter = newAdapter
                binding.rvApps.adapter = newAdapter
                binding.layoutEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

                val currentSearch = binding.etSearch.text
                if (!currentSearch.isNullOrEmpty()) {
                    newAdapter.filter.filter(currentSearch)
                }
            }
        }
    }
}
