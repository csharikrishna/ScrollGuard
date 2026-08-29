package com.scrollguard.parental

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.scrollguard.R
import com.scrollguard.data.parental.ParentalAppRestriction
import com.scrollguard.databinding.ItemParentalAppBinding

class ParentalAppAdapter(
    private var items: List<ParentalAppRestriction>,
    private val onAllowanceChanged: (ParentalAppRestriction, Int) -> Unit,
    private val onEnabledChanged: (ParentalAppRestriction, Boolean) -> Unit,
    private val onDeleteClicked: (ParentalAppRestriction) -> Unit
) : RecyclerView.Adapter<ParentalAppAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemParentalAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemParentalAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // `var`, not `val`: reassigned on every optimistic tap below so a run of rapid taps on
        // the same bound view stacks correctly from the last-displayed value instead of each
        // tap recomputing from the same stale bind-time value (which used to make N rapid taps
        // net one effective increment instead of N, since every write raced from the same base).
        var item = items[position]
        with(holder.binding) {
            fun renderAllowance(current: ParentalAppRestriction) {
                val allowanceMin = current.allowanceSeconds / 60
                val remainingMin = (current.remainingSeconds / 60).coerceAtLeast(0)
                tvAllowanceValue.text = "${allowanceMin}m"
                tvAllowanceSubtitle.text = "${remainingMin}m remaining (${allowanceMin}m limit)"
                val progress = if (current.allowanceSeconds > 0) {
                    ((current.remainingSeconds.toFloat() / current.allowanceSeconds.toFloat()) * 100)
                        .toInt().coerceIn(0, 100)
                } else {
                    0
                }
                progressRemaining.progress = progress
            }

            tvMonogram.text = item.appName.firstOrNull()?.uppercase() ?: "A"
            tvAppName.text = item.appName
            renderAllowance(item)

            // Switch (detach listener first to avoid re-triggering during bind)
            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = item.enabled
            switchEnabled.contentDescription = switchEnabled.context.getString(
                R.string.cd_restrict_app_format, item.appName
            )
            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                item = item.copy(enabled = isChecked)
                onEnabledChanged(item, isChecked)
            }

            // Steppers — update the local item and re-render immediately (optimistic UI);
            // onAllowanceChanged carries the already-stacked value to the caller, which
            // debounces the actual network write.
            btnMinus.setOnClickListener {
                item = item.copy(allowanceSeconds = (item.allowanceSeconds - (5 * 60)).coerceAtLeast(0))
                renderAllowance(item)
                onAllowanceChanged(item, item.allowanceSeconds)
            }

            btnPlus.setOnClickListener {
                item = item.copy(allowanceSeconds = (item.allowanceSeconds + (5 * 60)).coerceAtMost(24 * 3600))
                renderAllowance(item)
                onAllowanceChanged(item, item.allowanceSeconds)
            }

            btnDelete.setOnClickListener {
                onDeleteClicked(item)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newList: List<ParentalAppRestriction>) {
        items = newList
        notifyDataSetChanged()
    }
}
