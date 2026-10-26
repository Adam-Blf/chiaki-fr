// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.View
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.RecyclerView

/** False on Android TV boxes such as the Shield: navigation is by D-pad or gamepad only. */
val Context.hasTouchscreen: Boolean
	get() = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

/** Gamepad A behaves as the D-pad centre key, the "OK" of every standard Android widget. */
fun KeyEvent.asDpadCenter() = KeyEvent(
	downTime, eventTime, action, KeyEvent.KEYCODE_DPAD_CENTER,
	repeatCount, metaState, deviceId, scanCode, flags, source)

/** Gives focus to the first focusable child once the list has been laid out. */
fun RecyclerView.requestFocusOnFirstItem()
{
	doOnLayout {
		post {
			for(i in 0 until childCount)
			{
				val child = getChildAt(i)
				if(child.isFocusable && child.visibility == View.VISIBLE)
				{
					child.requestFocus()
					return@post
				}
			}
		}
	}
}
