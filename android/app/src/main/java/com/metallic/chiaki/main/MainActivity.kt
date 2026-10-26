// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.ActivityOptions
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.core.view.doOnLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.hasTouchscreen
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity

class MainActivity : TvActivity()
{
	private lateinit var viewModel: MainViewModel

	private lateinit var binding: ActivityMainBinding

	/** True until the player presses a key: only then the app may choose the focus on their behalf. */
	private var focusIsAutomatic = true

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		binding.registerButton.setOnClickListener { showRegistration() }
		binding.addManualButton.setOnClickListener { addManualConsole() }
		binding.discoverButton.setOnClickListener {
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
		}
		binding.settingsButton.setOnClickListener {
			startActivity(Intent(this, SettingsActivity::class.java))
		}
		binding.hintTextView.visibility = if(hasTouchscreen) View.GONE else View.VISIBLE

		viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })
			.get(MainViewModel::class.java)

		val recyclerViewAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::showHostActions)
		binding.hostsRecyclerView.adapter = recyclerViewAdapter
		// One horizontal row of tiles, like a game row
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
		// A cross-fade replaces the view holder, and the focused card would lose the focus with it
		(binding.hostsRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
		viewModel.displayHosts.observe(this, Observer {
			val top = binding.hostsRecyclerView.computeHorizontalScrollOffset() == 0
			recyclerViewAdapter.hosts = it
			if(top)
				binding.hostsRecyclerView.scrollToPosition(0)
			updateEmptyInfo()
			updateAutomaticFocus()
		})

		viewModel.discoveryActive.observe(this, Observer { active ->
			binding.discoverButton.setText(if(active) R.string.action_discover_on else R.string.action_discover_off)
			updateEmptyInfo()
		})

		updateAutomaticFocus()
	}

	/** Initial focus: the first console card when there is one, the "register" button otherwise. */
	private fun updateAutomaticFocus()
	{
		if(!focusIsAutomatic)
			return
		binding.hostsRecyclerView.doOnLayout {
			if(!focusIsAutomatic)
				return@doOnLayout
			val firstCard = binding.hostsRecyclerView.findViewHolderForAdapterPosition(0)?.itemView
			if(firstCard != null)
			{
				firstCard.requestFocus()
				focusIsAutomatic = false
			}
			else if(!binding.registerButton.hasFocus())
				binding.registerButton.requestFocus()
		}
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(event.action == KeyEvent.ACTION_DOWN)
			focusIsAutomatic = false
		return super.dispatchKeyEvent(event)
	}

	private fun updateEmptyInfo()
	{
		if(viewModel.displayHosts.value?.isEmpty() ?: true)
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
			val discoveryActive = viewModel.discoveryActive.value ?: false
			binding.emptyInfoTextView.setText(if(discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info)
		}
		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
	}

	private fun startWithReveal(intent: Intent, origin: View)
	{
		if(hasTouchscreen)
		{
			intent.putRevealExtra(origin, binding.rootLayout)
			startActivity(intent, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
		else
			startActivity(intent)
	}

	private fun addManualConsole()
	{
		startWithReveal(Intent(this, EditManualConsoleActivity::class.java), binding.addManualButton)
	}

	private fun showRegistration()
	{
		startWithReveal(Intent(this, RegistActivity::class.java), binding.registerButton)
	}

	private fun hostTriggered(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost != null)
		{
			fun connect() {
				val preferences = Preferences(this)
				val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, preferences.videoProfile, lowLatency = preferences.lowLatency)
				Intent(this, StreamActivity::class.java).let {
					it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
					it.putExtra(StreamActivity.EXTRA_CONSOLE_NAME, host.name)
					startActivity(it)
				}
			}

			if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
			{
				MaterialAlertDialogBuilder(this)
					.setMessage(R.string.alert_message_standby_wakeup)
					.setPositiveButton(R.string.action_wakeup) { _, _ ->
						wakeupHost(host)
					}
					.setNeutralButton(R.string.action_connect_immediately) { _, _ ->
						connect()
					}
					.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> }
					.create()
					.showWithFocus(DialogInterface.BUTTON_POSITIVE)
			}
			else
				connect()
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	/** Long press (or long OK) on a card: wake up, edit, delete. Replaces the overflow menu. */
	private fun showHostActions(host: DisplayHost)
	{
		val actions = mutableListOf<Pair<Int, () -> Unit>>()
		if(host.registeredHost != null)
			actions.add(R.string.action_wakeup to { wakeupHost(host) })
		if(host is ManualDisplayHost)
		{
			actions.add(R.string.action_edit to { editHost(host) })
			actions.add(R.string.action_delete to { deleteHost(host) })
		}
		if(actions.isEmpty())
		{
			hostTriggered(host)
			return
		}

		MaterialAlertDialogBuilder(this)
			.setTitle(host.name ?: host.host)
			.setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
				actions[which].second()
			}
			.setNegativeButton(R.string.action_cancel, null)
			.create()
			.showWithFocus(null)
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
		Toast.makeText(this, getString(R.string.toast_wakeup_sent, host.name ?: host.host), Toast.LENGTH_LONG).show()
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.showWithFocus(DialogInterface.BUTTON_NEGATIVE)
	}
}
