package com.scrollguard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.scrollguard.data.AppGroup
import com.scrollguard.databinding.ItemAppGroupBinding

class AppGroupAdapter(
    private var groups: List<AppGroup>,
    private var appCountMap: Map<String, Int>,
    private val onEditClicked: (AppGroup) -> Unit,
    private val onDeleteClicked: (AppGroup) -> Unit,
    private val onGroupCardClicked: (AppGroup) -> Unit
) : RecyclerView.Adapter<AppGroupAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemAppGroupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
        with(holder.binding) {
            tvGroupName.text = group.name

            val freeMin = group.freeDurationSec / 60
            val lockMin = group.lockDurationSec / 60
            tvCycleSummary.text = root.context.getString(R.string.group_cycle_format, freeMin, lockMin)

            val count = appCountMap[group.id] ?: 0
            tvAppCount.text = root.context.getString(R.string.assigned_apps_count, count)

            btnEdit.setOnClickListener { onEditClicked(group) }
            btnDelete.setOnClickListener { onDeleteClicked(group) }
            root.setOnClickListener { onGroupCardClicked(group) }
        }
    }

    override fun getItemCount(): Int = groups.size

    fun updateData(newGroups: List<AppGroup>, newCountMap: Map<String, Int>) {
        groups = newGroups
        appCountMap = newCountMap
        notifyDataSetChanged()
    }
}
