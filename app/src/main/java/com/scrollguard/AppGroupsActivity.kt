package com.scrollguard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.scrollguard.data.AppEntry
import com.scrollguard.data.AppGroup
import com.scrollguard.data.DataRepository
import com.scrollguard.databinding.ActivityAppGroupsBinding
import com.scrollguard.databinding.DialogEditGroupBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

class AppGroupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppGroupsBinding
    private lateinit var repository: DataRepository
    private var adapter: AppGroupAdapter? = null
    private var allMonitoredAppsList: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = DataRepository.getInstance(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rvGroups.layoutManager = LinearLayoutManager(this)

        adapter = AppGroupAdapter(
            groups = emptyList(),
            appCountMap = emptyMap(),
            onEditClicked = { group -> showEditGroupDialog(group) },
            onDeleteClicked = { group -> confirmDeleteGroup(group) },
            onGroupCardClicked = { group -> showAssignAppsDialog(group) }
        )
        binding.rvGroups.adapter = adapter

        binding.fabCreateGroup.setOnClickListener { showEditGroupDialog(null) }

        observeGroups()
    }

    private fun observeGroups() {
        lifecycleScope.launch {
            repository.allGroups.combine(repository.allMonitoredApps) { groups, apps ->
                allMonitoredAppsList = apps
                val countMap = apps.groupBy { it.groupId ?: "" }.mapValues { it.value.size }
                Pair(groups, countMap)
            }.collect { (groups, countMap) ->
                adapter?.updateData(groups, countMap)
                binding.tvEmptyGroups.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showEditGroupDialog(existingGroup: AppGroup?) {
        val dialogBinding = DialogEditGroupBinding.inflate(LayoutInflater.from(this))

        var freeMin = ((existingGroup?.freeDurationSec ?: 1800L) / 60).toInt()
        var lockMin = ((existingGroup?.lockDurationSec ?: 600L) / 60).toInt()
        var allowMin = ((existingGroup?.allowDurationSec ?: 120L) / 60).toInt()

        if (existingGroup != null) {
            dialogBinding.etGroupName.setText(existingGroup.name)
        }
        dialogBinding.tvFreeMinutes.text = freeMin.toString()
        dialogBinding.tvLockMinutes.text = lockMin.toString()
        dialogBinding.tvAllowMinutes.text = allowMin.toString()

        // Gives a distinct "denied" haptic when a tap is already at the min/max clamp, instead
        // of silently doing nothing — matches MainActivity's steppers (adjustTimer/
        // performClampedFeedback).
        fun clampedFeedback(v: View) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
            }
        }

        dialogBinding.btnFreeMinus.setOnClickListener { v ->
            val next = (freeMin - 5).coerceAtLeast(1)
            if (next == freeMin) clampedFeedback(v)
            freeMin = next
            dialogBinding.tvFreeMinutes.text = freeMin.toString()
        }
        dialogBinding.btnFreePlus.setOnClickListener { v ->
            val next = (freeMin + 5).coerceAtMost(1440)
            if (next == freeMin) clampedFeedback(v)
            freeMin = next
            dialogBinding.tvFreeMinutes.text = freeMin.toString()
        }

        dialogBinding.btnLockMinus.setOnClickListener { v ->
            val next = (lockMin - 5).coerceAtLeast(1)
            if (next == lockMin) clampedFeedback(v)
            lockMin = next
            dialogBinding.tvLockMinutes.text = lockMin.toString()
        }
        dialogBinding.btnLockPlus.setOnClickListener { v ->
            val next = (lockMin + 5).coerceAtMost(1440)
            if (next == lockMin) clampedFeedback(v)
            lockMin = next
            dialogBinding.tvLockMinutes.text = lockMin.toString()
        }

        dialogBinding.btnAllowMinus.setOnClickListener { v ->
            val next = (allowMin - 1).coerceAtLeast(1)
            if (next == allowMin) clampedFeedback(v)
            allowMin = next
            dialogBinding.tvAllowMinutes.text = allowMin.toString()
        }
        dialogBinding.btnAllowPlus.setOnClickListener { v ->
            val next = (allowMin + 1).coerceAtMost(60)
            if (next == allowMin) clampedFeedback(v)
            allowMin = next
            dialogBinding.tvAllowMinutes.text = allowMin.toString()
        }

        AlertDialog.Builder(this)
            .setTitle(if (existingGroup != null) getString(R.string.edit_group) else getString(R.string.create_group))
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = dialogBinding.etGroupName.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.error_group_name_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val group = existingGroup?.copy(
                    name = name,
                    freeDurationSec = freeMin * 60L,
                    lockDurationSec = lockMin * 60L,
                    allowDurationSec = allowMin * 60L
                ) ?: AppGroup(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    freeDurationSec = freeMin * 60L,
                    lockDurationSec = lockMin * 60L,
                    allowDurationSec = allowMin * 60L
                )

                lifecycleScope.launch {
                    repository.addGroup(group)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAssignAppsDialog(group: AppGroup) {
        if (allMonitoredAppsList.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_select_apps), Toast.LENGTH_SHORT).show()
            return
        }

        val appNames = allMonitoredAppsList.map { it.appName }.toTypedArray()
        val checkedItems = allMonitoredAppsList.map { it.groupId == group.id }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.assign_apps_to_group_format, group.name))
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    for (i in allMonitoredAppsList.indices) {
                        val app = allMonitoredAppsList[i]
                        val isChecked = checkedItems[i]
                        if (isChecked && app.groupId != group.id) {
                            repository.setAppGroup(app.packageName, group.id)
                        } else if (!isChecked && app.groupId == group.id) {
                            repository.setAppGroup(app.packageName, null)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteGroup(group: AppGroup) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_group_title_format, group.name))
            .setMessage(getString(R.string.delete_group_confirm))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteGroup(group.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
