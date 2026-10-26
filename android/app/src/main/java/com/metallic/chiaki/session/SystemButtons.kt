// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.os.Handler
import com.metallic.chiaki.lib.ControllerState

/**
 * Select and Start carry several meanings on a controller without a touchpad or PS button:
 *
 * - Select tap = Share, Select held 500 ms = touchpad click
 * - Start = Options, held 2 s = open the stream menu
 * - Select + Start tap = PS, Select + Start held 1.5 s = open the stream menu
 *
 * Because of that, Select is only forwarded on release (tap) or after the hold delay.
 */
internal class SystemButtons(
	private val handler: Handler,
	private val setButtons: (set: UInt, clear: UInt) -> Unit,
	private val pulse: (UInt) -> Unit,
	private val menuRequested: () -> Unit
)
{
	companion object
	{
		const val TOUCHPAD_HOLD_MS = 500L
		const val START_MENU_HOLD_MS = 2000L
		const val CHORD_MENU_HOLD_MS = 1500L
	}

	private var selectHeld = false
	private var startHeld = false
	private var selectConsumed = false
	private var touchpadHeld = false
	private var chordActive = false
	private var chordMenuFired = false

	private val touchpadRunnable = Runnable {
		if(selectHeld && !chordActive)
		{
			touchpadHeld = true
			setButtons(ControllerState.BUTTON_TOUCHPAD, 0U)
		}
	}
	private val startMenuRunnable = Runnable {
		if(startHeld && !chordActive)
			menuRequested()
	}
	private val chordMenuRunnable = Runnable {
		if(chordActive)
		{
			chordMenuFired = true
			menuRequested()
		}
	}

	fun onSelect(down: Boolean)
	{
		if(down)
		{
			if(selectHeld)
				return
			selectHeld = true
			selectConsumed = false
			if(startHeld)
				beginChord()
			else
				handler.postDelayed(touchpadRunnable, TOUCHPAD_HOLD_MS)
		}
		else
		{
			if(!selectHeld)
				return
			selectHeld = false
			handler.removeCallbacks(touchpadRunnable)
			when
			{
				chordActive -> endChord()
				selectConsumed -> {}
				touchpadHeld ->
				{
					touchpadHeld = false
					setButtons(0U, ControllerState.BUTTON_TOUCHPAD)
				}
				else -> pulse(ControllerState.BUTTON_SHARE)
			}
		}
	}

	fun onStart(down: Boolean)
	{
		if(down)
		{
			if(startHeld)
				return
			startHeld = true
			if(selectHeld)
				beginChord()
			else
			{
				setButtons(ControllerState.BUTTON_OPTIONS, 0U)
				handler.postDelayed(startMenuRunnable, START_MENU_HOLD_MS)
			}
		}
		else
		{
			if(!startHeld)
				return
			startHeld = false
			handler.removeCallbacks(startMenuRunnable)
			if(chordActive)
				endChord()
			else
				setButtons(0U, ControllerState.BUTTON_OPTIONS)
		}
	}

	private fun beginChord()
	{
		chordActive = true
		chordMenuFired = false
		selectConsumed = true
		touchpadHeld = false
		handler.removeCallbacks(touchpadRunnable)
		handler.removeCallbacks(startMenuRunnable)
		// the console must see neither Options nor Share nor touchpad for the chord
		setButtons(0U, ControllerState.BUTTON_OPTIONS or ControllerState.BUTTON_TOUCHPAD)
		handler.postDelayed(chordMenuRunnable, CHORD_MENU_HOLD_MS)
	}

	private fun endChord()
	{
		chordActive = false
		handler.removeCallbacks(chordMenuRunnable)
		if(!chordMenuFired)
			pulse(ControllerState.BUTTON_PS)
	}

	/** Forgets every held state without emitting anything. */
	fun reset()
	{
		handler.removeCallbacks(touchpadRunnable)
		handler.removeCallbacks(startMenuRunnable)
		handler.removeCallbacks(chordMenuRunnable)
		selectHeld = false
		startHeld = false
		selectConsumed = false
		touchpadHeld = false
		chordActive = false
		chordMenuFired = false
	}
}
