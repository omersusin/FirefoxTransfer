package com.browsermover.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.browsermover.app.R
import com.browsermover.app.model.BrowserInfo
import com.google.android.material.card.MaterialCardView

class BrowserAdapter(
    private val onSelected: (BrowserInfo) -> Unit
) : ListAdapter<BrowserInfo, BrowserAdapter.VH>(Diff()) {

    private var selectedPos = RecyclerView.NO_POSITION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_browser, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position == selectedPos)
        holder.itemView.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val old = selectedPos
            selectedPos = pos
            if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
            notifyItemChanged(pos)
            onSelected(getItem(pos))
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val card: MaterialCardView = v.findViewById(R.id.card_browser)
        private val icon: ImageView = v.findViewById(R.id.img_browser_icon)
        private val name: TextView = v.findViewById(R.id.txt_browser_name)
        private val pkg: TextView = v.findViewById(R.id.txt_package_name)
        private val engine: TextView = v.findViewById(R.id.txt_engine_type)
        private val version: TextView = v.findViewById(R.id.txt_version)

        fun bind(b: BrowserInfo, selected: Boolean) {
            icon.setImageDrawable(b.icon ?: ContextCompat.getDrawable(itemView.context, android.R.drawable.sym_def_app_icon))
            name.text = b.appName
            pkg.text = b.packageName
            engine.text = b.engineLabel
            version.text = if (b.versionName.isNotBlank()) "v${b.versionName}" else ""

            val ctx = itemView.context
            if (selected) {
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_card_selected))
                card.strokeColor = ContextCompat.getColor(ctx, R.color.accent_blue)
                card.strokeWidth = 4
                card.cardElevation = 8f
            } else {
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.bg_card))
                card.strokeColor = ContextCompat.getColor(ctx, R.color.border)
                card.strokeWidth = 1
                card.cardElevation = 2f
            }
        }
    }

    class Diff : DiffUtil.ItemCallback<BrowserInfo>() {
        override fun areItemsTheSame(a: BrowserInfo, b: BrowserInfo) = a.packageName == b.packageName
        override fun areContentsTheSame(a: BrowserInfo, b: BrowserInfo) = a == b
    }
}
