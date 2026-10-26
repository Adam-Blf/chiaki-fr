// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ext.enableOkLongPress
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.databinding.ItemRegisteredHostBinding

class SettingsRegisteredHostsAdapter: RecyclerView.Adapter<SettingsRegisteredHostsAdapter.ViewHolder>()
{
	/** Long press (or long OK) on a row: the TV replacement for the swipe. */
	var deleteCallback: ((RegisteredHost) -> Unit)? = null

	class ViewHolder(val binding: ItemRegisteredHostBinding): RecyclerView.ViewHolder(binding.root)

	var hosts: List<RegisteredHost> = listOf()
		set(value)
		{
			field = value
			notifyDataSetChanged()
		}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
		= ViewHolder(ItemRegisteredHostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun getItemCount() = hosts.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val host = hosts[position]
		val context = holder.itemView.context
		val consoleType = context.getString(if(host.target.isPS5) R.string.console_type_ps5 else R.string.console_type_ps4)
		holder.binding.nameTextView.text = "${host.serverNickname} ($consoleType)"
		holder.binding.summaryTextView.text = host.serverMac.toString()
		holder.binding.root.setOnLongClickListener {
			deleteCallback?.let { callback -> callback(host) }
			true
		}
		holder.binding.root.enableOkLongPress()
	}
}
