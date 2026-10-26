// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.databinding.ItemDisplayHostBinding

/**
 * Identity is type + id + host (the manual entry id for manual consoles), so a discovery refresh
 * updates the card in place instead of replacing it, and the D-pad focus stays where it was.
 */
class DisplayHostDiffCallback(val old: List<DisplayHost>, val new: List<DisplayHost>): DiffUtil.Callback()
{
	override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean
	{
		val a = old[oldItemPosition]
		val b = new[newItemPosition]
		return when
		{
			a is ManualDisplayHost && b is ManualDisplayHost -> a.manualHost.id == b.manualHost.id
			a is DiscoveredDisplayHost && b is DiscoveredDisplayHost -> a.id == b.id && a.host == b.host
			else -> false
		}
	}

	override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = (old[oldItemPosition] == new[newItemPosition])
	override fun getOldListSize() = old.size
	override fun getNewListSize() = new.size
}

class DisplayHostRecyclerViewAdapter(
	val clickCallback: (DisplayHost) -> Unit,
	val longClickCallback: (DisplayHost) -> Unit
): RecyclerView.Adapter<DisplayHostRecyclerViewAdapter.ViewHolder>()
{
	var hosts: List<DisplayHost> = listOf()
		set(value)
		{
			val diff = DiffUtil.calculateDiff(DisplayHostDiffCallback(field, value))
			field = value
			diff.dispatchUpdatesTo(this)
		}

	class ViewHolder(val binding: ItemDisplayHostBinding): RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
		= ViewHolder(ItemDisplayHostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun getItemCount() = hosts.count()

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val context = holder.itemView.context
		val host = hosts[position]
		val status = host.status
		val statusColor = ContextCompat.getColor(context, status.color)
		holder.binding.also {
			it.consoleTypeTextView.setText(if(host.isPS5) R.string.console_type_ps5 else R.string.console_type_ps4)
			it.nameTextView.text = host.name ?: host.host
			it.hostTextView.text = context.getString(R.string.display_host_host, host.host)

			it.stateChipTextView.setText(status.label)
			it.stateChipTextView.setTextColor(statusColor)
			it.stateChipTextView.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(statusColor, 0x2E))
			it.stateChipTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(status.icon, 0, 0, 0)
			TextViewCompat.setCompoundDrawableTintList(it.stateChipTextView, ColorStateList.valueOf(statusColor))

			val runningApp = (host as? DiscoveredDisplayHost)?.discoveredHost?.let { discoveredHost ->
				if(discoveredHost.runningAppName != null || discoveredHost.runningAppTitleid != null)
					context.getString(R.string.display_host_app_title_id, discoveredHost.runningAppName ?: "", discoveredHost.runningAppTitleid ?: "")
				else
					null
			}
			it.bottomInfoTextView.text = runningApp ?: ""
			it.bottomInfoTextView.visibility = if(runningApp != null) View.VISIBLE else View.GONE

			it.actionTextView.setText(status.action)

			it.root.setOnClickListener { _ -> clickCallback(host) }
			it.root.setOnLongClickListener { _ ->
				longClickCallback(host)
				true
			}
		}
	}
}
