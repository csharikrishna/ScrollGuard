package com.scrollguard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.scrollguard.databinding.ActivityParentalAppPickerBinding
import com.scrollguard.parental.ParentalCatalogItem
import com.scrollguard.parental.ParentalPickerAdapter
import com.scrollguard.parental.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ParentalAppPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FAMILY_ID = "extra_family_id"
    }

    private lateinit var binding: ActivityParentalAppPickerBinding
    private lateinit var syncEngine: SyncEngine
    private var familyId: String? = null
    private var adapter: ParentalPickerAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        familyId = intent.getStringExtra(EXTRA_FAMILY_ID)
        syncEngine = SyncEngine(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rvApps.layoutManager = LinearLayoutManager(this)

        setupSearch()
        loadChildCatalog()
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

    private fun loadChildCatalog() {
        val fid = familyId ?: run {
            Toast.makeText(this, getString(R.string.error_family_id_missing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.layoutEmptyState.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val catalogResult = syncEngine.readChildCatalog(fid)
            val appsList = catalogResult.getOrNull() ?: emptyList()

            // Also read existing restricted apps from Firestore to know which ones are already checked
            val existingRestrictions = try {
                val snapshot = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("families").document(fid)
                    .collection("config").document("current")
                    .collection("apps").get().await()
                snapshot.documents.map { it.id }.toSet()
            } catch (e: Exception) {
                emptySet()
            }

            val items = appsList.mapNotNull { map ->
                val pkg = map["packageName"] as? String ?: return@mapNotNull null
                val label = map["label"] as? String ?: pkg
                ParentalCatalogItem(
                    packageName = pkg,
                    label = label,
                    isSelected = existingRestrictions.contains(pkg)
                )
            }.sortedBy { it.label.lowercase() }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE

                if (items.isEmpty()) {
                    binding.layoutEmptyState.visibility = View.VISIBLE
                    return@withContext
                }

                val newAdapter = ParentalPickerAdapter(items) { item, isChecked ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (isChecked) {
                            // Add default 60 min restriction
                            syncEngine.writeAppRestriction(
                                fid, item.packageName, item.label, true, 3600
                            )
                        } else {
                            // Remove restriction
                            syncEngine.removeAppRestriction(fid, item.packageName)
                        }
                    }
                }

                newAdapter.onVisibleCountChanged = { count ->
                    binding.layoutEmptyState.visibility = if (count == 0) View.VISIBLE else View.GONE
                }

                adapter = newAdapter
                binding.rvApps.adapter = newAdapter

                val currentSearch = binding.etSearch.text
                if (!currentSearch.isNullOrEmpty()) {
                    newAdapter.filter.filter(currentSearch)
                }
            }
        }
    }
}
