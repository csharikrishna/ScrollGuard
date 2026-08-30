package com.scrollguard.parental

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.recyclerview.widget.RecyclerView
import com.scrollguard.databinding.ItemParentalPickerBinding
import java.util.Locale

data class ParentalCatalogItem(
    val packageName: String,
    val label: String,
    var isSelected: Boolean
)

class ParentalPickerAdapter(
    private val allItems: List<ParentalCatalogItem>,
    private val onAppToggled: (ParentalCatalogItem, Boolean) -> Unit
) : RecyclerView.Adapter<ParentalPickerAdapter.ViewHolder>(), Filterable {

    private var filteredList = allItems.toList()
    var onVisibleCountChanged: ((Int) -> Unit)? = null

    class ViewHolder(val binding: ItemParentalPickerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemParentalPickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredList[position]
        with(holder.binding) {
            tvMonogram.text = item.label.firstOrNull()?.uppercase() ?: "A"
            tvAppName.text = item.label
            tvPackageName.text = item.packageName

            cbSelected.setOnCheckedChangeListener(null)
            cbSelected.isChecked = item.isSelected

            root.setOnClickListener {
                cbSelected.isChecked = !cbSelected.isChecked
            }

            cbSelected.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                onAppToggled(item, isChecked)
            }
        }
    }

    override fun getItemCount(): Int = filteredList.size

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                val results = if (query.isEmpty()) {
                    allItems
                } else {
                    allItems.filter {
                        it.label.lowercase(Locale.getDefault()).contains(query) ||
                                it.packageName.lowercase(Locale.getDefault()).contains(query)
                    }
                }
                return FilterResults().apply {
                    values = results
                    count = results.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                filteredList = results?.values as? List<ParentalCatalogItem> ?: emptyList()
                notifyDataSetChanged()
                onVisibleCountChanged?.invoke(filteredList.size)
            }
        }
    }
}
