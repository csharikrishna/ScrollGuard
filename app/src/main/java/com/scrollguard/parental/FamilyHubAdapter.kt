package com.scrollguard.parental

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scrollguard.R

data class FamilyHubItem(
    val familyId: String,
    val childDeviceName: String
)

class FamilyHubAdapter(
    private val onItemClick: (FamilyHubItem) -> Unit,
    private val onEditClick: (FamilyHubItem) -> Unit
) : RecyclerView.Adapter<FamilyHubAdapter.ViewHolder>() {

    private var items: List<FamilyHubItem> = emptyList()

    fun submitList(newItems: List<FamilyHubItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_child_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceName: TextView = itemView.findViewById(R.id.tvItemDeviceName)
        private val tvDeviceStatus: TextView = itemView.findViewById(R.id.tvItemDeviceStatus)
        private val btnEdit: View = itemView.findViewById(R.id.btnEditChildName)

        fun bind(item: FamilyHubItem) {
            tvDeviceName.text = item.childDeviceName
            
            // We just set it to empty or basic since real-time status 
            // is synced when they enter the dashboard
            tvDeviceStatus.text = "Paired Device"

            itemView.setOnClickListener {
                onItemClick(item)
            }
            
            btnEdit.setOnClickListener {
                onEditClick(item)
            }
        }
    }
}
