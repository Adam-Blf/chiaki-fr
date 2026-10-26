// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.DialogInterface
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import com.metallic.chiaki.common.ext.asDpadCenter

/**
 * Shows the dialog with the gamepad mapping (A = OK, B = Cancel) and the focus on [focusedButton].
 * Destructive dialogs pass BUTTON_NEGATIVE so that a stray OK press never deletes anything,
 * list dialogs pass null to focus the first entry.
 */
fun AlertDialog.showWithFocus(focusedButton: Int? = DialogInterface.BUTTON_POSITIVE)
{
	setOnKeyListener { dialog, keyCode, event ->
		when(keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_A ->
			{
				window?.decorView?.dispatchKeyEvent(event.asDpadCenter())
				true
			}
			KeyEvent.KEYCODE_BUTTON_B ->
			{
				if(event.action == KeyEvent.ACTION_UP)
					dialog.cancel()
				true
			}
			else -> false
		}
	}
	setOnShowListener {
		if(focusedButton != null)
			getButton(focusedButton)?.requestFocus()
		else
			listView?.requestFocus()
	}
	show()
}
