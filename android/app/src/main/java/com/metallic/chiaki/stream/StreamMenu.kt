// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.view.View
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.session.MenuAction

/**
 * Right-hand side menu shown over the stream. Navigation is driven by [MenuAction]s coming from
 * StreamInput (remote, gamepad) or by taps; Android focus is deliberately not used so that a
 * gamepad stick, a D-pad hat and a remote all behave the same.
 */
class StreamMenu(
	private val binding: ActivityStreamBinding,
	private val onOpenChanged: (Boolean) -> Unit,
	private val onStatsChanged: (Boolean) -> Unit,
	private val onSleep: () -> Unit,
	private val onQuit: () -> Unit)
{
	private companion object
	{
		const val ITEM_STATS = 0
		const val ITEM_DISPLAY = 1
		const val ITEM_RESUME = 2
		const val ITEM_SLEEP = 3
		const val ITEM_QUIT = 4
	}

	private val items: List<View> = listOf(
		binding.menuItemStats, binding.menuItemDisplay, binding.menuItemResume, binding.menuItemSleep, binding.menuItemQuit)
	private val displayButtonIds = listOf(
		R.id.display_mode_normal_button, R.id.display_mode_zoom_button, R.id.display_mode_stretch_button)
	private var selected = ITEM_RESUME

	val isOpen: Boolean get() = binding.streamMenuRoot.visibility == View.VISIBLE

	var statsEnabled: Boolean
		get() = binding.menuStatsSwitch.isChecked
		set(value) { binding.menuStatsSwitch.isChecked = value }

	init
	{
		binding.streamMenuRoot.setOnClickListener { close() }
		items.forEachIndexed { index, view ->
			view.setOnClickListener {
				select(index)
				activate(index)
			}
		}
	}

	fun open()
	{
		if(isOpen)
			return
		refreshDisplayValue()
		select(ITEM_RESUME)
		binding.streamMenuRoot.visibility = View.VISIBLE
		onOpenChanged(true)
	}

	fun close()
	{
		if(!isOpen)
			return
		binding.streamMenuRoot.visibility = View.GONE
		onOpenChanged(false)
	}

	fun toggle() = if(isOpen) close() else open()

	fun onAction(action: MenuAction)
	{
		when(action)
		{
			MenuAction.UP -> select(Math.floorMod(selected - 1, items.size))
			MenuAction.DOWN -> select(Math.floorMod(selected + 1, items.size))
			MenuAction.LEFT -> if(selected == ITEM_DISPLAY) cycleDisplayMode(-1) else if(selected == ITEM_STATS) setStats(false)
			MenuAction.RIGHT -> if(selected == ITEM_DISPLAY) cycleDisplayMode(1) else if(selected == ITEM_STATS) setStats(true)
			MenuAction.OK -> activate(selected)
			MenuAction.CLOSE -> close()
		}
	}

	private fun select(index: Int)
	{
		selected = index
		items.forEachIndexed { i, view -> view.isSelected = i == index }
	}

	private fun activate(index: Int)
	{
		when(index)
		{
			ITEM_STATS -> setStats(!statsEnabled)
			ITEM_DISPLAY -> cycleDisplayMode(1)
			ITEM_RESUME -> close()
			ITEM_SLEEP -> onSleep()
			ITEM_QUIT -> onQuit()
		}
	}

	private fun setStats(enabled: Boolean)
	{
		if(statsEnabled == enabled)
			return
		statsEnabled = enabled
		onStatsChanged(enabled)
	}

	/** Reuses the display mode toggle group, which stays the single source of truth for the transform. */
	private fun cycleDisplayMode(step: Int)
	{
		val current = displayButtonIds.indexOf(binding.displayModeToggle.checkedButtonId).coerceAtLeast(0)
		binding.displayModeToggle.check(displayButtonIds[Math.floorMod(current + step, displayButtonIds.size)])
		refreshDisplayValue()
	}

	private fun refreshDisplayValue()
	{
		binding.menuDisplayValue.setText(when(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		{
			TransformMode.FIT -> R.string.stream_display_mode_fit
			TransformMode.ZOOM -> R.string.stream_display_mode_zoom
			TransformMode.STRETCH -> R.string.stream_display_mode_stretch
		})
	}
}
