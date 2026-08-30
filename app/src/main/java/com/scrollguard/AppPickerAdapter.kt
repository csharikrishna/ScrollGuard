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
                binding.tvUsageTime.text = when {
                    hours > 0 -> binding.root.context.getString(R.string.usage_today_hours_format, hours, mins)
                    mins > 0 -> binding.root.context.getString(R.string.usage_today_minutes_format, mins)
                    // Real, non-zero usage (e.g. 50s) would otherwise integer-divide down to
                    // "0m" and look like a broken feature rather than a genuinely small value.
                    else -> binding.root.context.getString(R.string.usage_today_less_than_minute)
                }
            } else {
                binding.tvUsageTime.visibility = android.view.View.GONE
            }

            binding.cbMonitored.setOnCheckedChangeListener(null)
            binding.cbMonitored.isChecked = item.isMonitored
            // Recycled ViewHolders can carry over SwitchMaterial's in-flight thumb-position
            // animation from whatever the view last displayed (e.g. mid-toggle when the list
            // was re-filtered by search) — isChecked is set correctly above, but the widget
            // can keep rendering the previous item's visual on/off position. Calling
            // jumpDrawablesToCurrentState() synchronously here (the previous attempt) mostly
            // didn't help: at bind() time the view has often just been reattached/rebound and
            // hasn't completed its layout pass for the new position yet, so the jump can act
            // before there's anything correct to jump to. Posting it ensures it runs after
            // layout, once the view is genuinely settled at its (possibly new) position.
            binding.cbMonitored.post { binding.cbMonitored.jumpDrawablesToCurrentState() }

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

    // Root-cause fix for the checkbox-shows-wrong-state bug: RecyclerView pools recycled
    // ViewHolders by view type and will silently hand back a view that was last displaying a
    // DIFFERENT item's checked state — SwitchMaterial's thumb-slide is driven by its own
    // internal position animator (not the standard Drawable-state mechanism), which can leave
    // that recycled view's THUMB rendered at its old position even after isChecked is set
    // correctly in bind() (confirmed on-device: the switch visually showed checked while a tap
    // on it actually flipped the real value from false to true). Keying view type on the
    // checked state means a view that last rendered "on" can never be recycled into a row that
    // should render "off" (or vice versa) — RecyclerView keeps separate recycle pools per type
    // — which removes the stale-recycling condition at its source instead of racing the
    // animation after the fact.
    override fun getItemViewType(position: Int): Int = if (filteredItems[position].isMonitored) 1 else 0

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
