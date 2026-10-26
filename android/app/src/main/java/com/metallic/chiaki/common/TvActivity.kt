// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.common.ext.asDpadCenter

/**
 * Base class of every non-stream screen: gamepad A acts as OK and B as Back,
 * so the whole app is usable with a remote or a controller and no touchscreen.
 */
abstract class TvActivity: AppCompatActivity()
{
	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(event.asDpadCenter())
			KeyEvent.KEYCODE_BUTTON_B ->
			{
				if(event.action == KeyEvent.ACTION_UP)
					onBackPressedDispatcher.onBackPressed()
				return true
			}
		}
		return super.dispatchKeyEvent(event)
	}
}
