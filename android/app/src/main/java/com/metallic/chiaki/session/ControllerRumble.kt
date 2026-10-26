// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import kotlin.math.max

/**
 * Sends the console rumble to the gamepad in the player's hands.
 *
 * Order of preference:
 * 1. API 31+: the controller's VibratorManager, one effect per motor
 * 2. the controller's single Vibrator (the only path on Android 11, so on the Shield TV)
 * 3. the device vibrator (phone), as before
 */
@Suppress("DEPRECATION")
class ControllerRumble(context: Context)
{
	companion object
	{
		private const val DURATION_MS = 1000L
	}

	private val systemVibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
	private var lastDevice: InputDevice? = null

	/** [left] and [right] are 0..255, 0 stops the motor. */
	fun rumble(device: InputDevice?, left: Int, right: Int)
	{
		cancel()
		val l = left.coerceIn(0, 255)
		val r = right.coerceIn(0, 255)
		if(l == 0 && r == 0)
			return
		lastDevice = device
		if(device != null)
		{
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && rumbleManager(device, l, r))
				return
			if(rumbleSingle(device.vibrator, max(l, r)))
				return
		}
		rumbleSingle(systemVibrator, ((l + r) / 2).coerceAtLeast(1))
	}

	fun cancel()
	{
		val device = lastDevice
		lastDevice = null
		if(device != null)
		{
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
				runCatching { device.vibratorManager.cancel() }
			runCatching { device.vibrator.cancel() }
		}
		runCatching { systemVibrator?.cancel() }
	}

	private fun rumbleManager(device: InputDevice, left: Int, right: Int): Boolean
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return false
		return runCatching {
			val manager: VibratorManager = device.vibratorManager
			val ids = manager.vibratorIds
			if(ids.size < 2)
				return false
			val combined = CombinedVibration.startParallel()
			// the first motor is the heavy (left) one, the second the light (right) one
			if(left > 0)
				combined.addVibrator(ids[0], VibrationEffect.createOneShot(DURATION_MS, left))
			if(right > 0)
				combined.addVibrator(ids[1], VibrationEffect.createOneShot(DURATION_MS, right))
			manager.vibrate(combined.combine())
			true
		}.getOrDefault(false)
	}

	@Suppress("DEPRECATION")
	private fun rumbleSingle(vibrator: Vibrator?, amplitude: Int): Boolean
	{
		if(vibrator == null || !vibrator.hasVibrator())
			return false
		return runCatching {
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				vibrator.vibrate(VibrationEffect.createOneShot(DURATION_MS, amplitude.coerceIn(1, 255)))
			else
				vibrator.vibrate(DURATION_MS)
			true
		}.getOrDefault(false)
	}
}
