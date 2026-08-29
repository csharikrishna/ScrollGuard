package com.scrollguard

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.recyclerview.widget.RecyclerView
import com.scrollguard.data.AppPickerItem
import com.scrollguard.databinding.ItemAppPickerBinding
import java.util.Locale

class AppPickerAdapter(
    private var allItems: List<AppPickerItem>,
    private val onAppSelected: (AppPickerItem, Boolean) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>(), Filterable {

    private var filteredItems: List<AppPickerItem> = allItems

    /** Notified with the current visible row count after every filter pass, so the Activity
     *  can show/hide the "no apps found" empty state for search results that match nothing. */
    var onVisibleCountChanged: ((Int) -> Unit)? = null

    // FIX M5: Must call setHasStableIds(true) for getItemId() to take effect.
    init { setHasStableIds(true) }

    inner class ViewHolder(private val binding: ItemAppPickerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppPickerItem) {
            binding.ivIcon.setImageDrawable(item.icon)
            // Each icon represents a specific, meaningful app — unlike purely decorative
            // icons elsewhere in the app, this one gets a real per-item description.
            binding.ivIcon.contentDescription = item.appName
            binding.tvAppName.text = item.appName

            if (item.usageTimeMillis > 0) {
                binding.tvUsageTime.visibility = android.view.View.VISIBLE
                val hours = item.usageTimeMillis / (1000 * 60 * 60)
                val mins = (item.usageTimeMillis / (1000 * 60)) % 60
                binding.tvUsageTime.text = if (hours > 0) {
                    binding.root.context.getString(R.string.usage_today_hours_format, hours, mins)
                } else {
                    binding.root.context.getString(R.string.usage_today_minutes_format, mins)
                }
            } else {
                binding.tvUsageTime.visibility = android.view.View.GONE
            }

            binding.cbMonitored.setOnCheckedChangeListener(null)
            binding.cbMonitored.isChecked = item.isMonitored

            binding.root.setOnClickListener {
                binding.cbMonitored.toggle()
            }

            binding.cbMonitored.setOnCheckedChangeListener { _, isChecked ->
                item.isMonitored = isChecked
                onAppSelected(item, isChecked)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredItems[position])
    }

    override fun getItemCount(): Int = filteredItems.size

    // FIX M6: Use toLong() with unsigned mask to reduce hash collision risk.
    // Plain hashCode().toLong() can collide; masking to 32-bit unsigned range helps.
    override fun getItemId(position: Int): Long =
        filteredItems[position].packageName.hashCode().toLong() and 0xFFFFFFFFL

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.lowercase(Locale.getDefault()) ?: ""
                val filtered = if (query.isEmpty()) allItems
                else allItems.filter {
                    it.appName.lowercase(Locale.getDefault()).contains(query) ||
                            it.packageName.lowercase(Locale.getDefault()).contains(query)
                }
                return FilterResults().apply { values = filtered }
            }

            @SuppressLint("NotifyDataSetChanged")
            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                filteredItems = results?.values as? List<AppPickerItem> ?: allItems
                notifyDataSetChanged()
                onVisibleCountChanged?.invoke(filteredItems.size)
            }
        }
    }
}
