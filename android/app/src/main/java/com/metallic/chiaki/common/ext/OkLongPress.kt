// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration

private val OK_KEYS = setOf(
	KeyEvent.KEYCODE_DPAD_CENTER,
	KeyEvent.KEYCODE_ENTER,
	KeyEvent.KEYCODE_NUMPAD_ENTER,
	KeyEvent.KEYCODE_BUTTON_A)

/**
 * Tells a short press of OK (D-pad centre, Enter, gamepad A) from a long one on a TV remote or a gamepad.
 *
 * A short press, released before the system long-press timeout, calls [View.performClick].
 * Holding OK calls [View.performLongClick] as soon as the timeout elapses, whether or not the remote
 * sends key repeats, and the release that follows is swallowed: it never turns into a click.
 * A key-up that was not preceded by a key-down on this view (focus arrived while OK was held) does nothing.
 */
fun View.enableOkLongPress()
{
	setOnKeyListener(OkLongPressKeyListener())
}

private class OkLongPressKeyListener: View.OnKeyListener
{
	private var tracking = false
	private var longPressFired = false
	private var pendingLongPress: Runnable? = null

	override fun onKey(view: View, keyCode: Int, event: KeyEvent): Boolean
	{
		if(keyCode !in OK_KEYS)
			return false
		when(event.action)
		{
			KeyEvent.ACTION_DOWN ->
			{
				// Key repeats of a held OK are consumed here, they must not reach the default click handling
				if(event.repeatCount == 0)
					start(view)
			}
			KeyEvent.ACTION_UP ->
			{
				val shortPress = tracking && !longPressFired && !event.isCanceled
				finish(view)
				if(shortPress)
					view.performClick()
			}
		}
		return true
	}

	private fun start(view: View)
	{
		finish(view)
		tracking = true
		view.isPressed = true
		val fire = Runnable {
			pendingLongPress = null
			if(tracking && view.isFocused)
			{
				longPressFired = true
				view.isPressed = false
				view.performLongClick()
			}
		}
		pendingLongPress = fire
		view.postDelayed(fire, ViewConfiguration.getLongPressTimeout().toLong())
	}

	private fun finish(view: View)
	{
		pendingLongPress?.let { view.removeCallbacks(it) }
		pendingLongPress = null
		tracking = false
		longPressFired = false
		view.isPressed = false
	}
}
