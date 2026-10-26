// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Axis layout of one gamepad, detected from the motion ranges the device reports.
 *
 * Android has no standard layout: the right stick sits on Z/RZ (Xbox, DualShock, Shield) or on
 * RX/RY (some generic pads), and the analog triggers on LTRIGGER/RTRIGGER, BRAKE/GAS, or on the
 * pair of axes the right stick does not use.
 */
internal class GamepadLayout(device: InputDevice?)
{
	class Trigger(private val axis: Int, private val min: Float, private val max: Float)
	{
		fun read(event: MotionEvent): Float
		{
			val range = max - min
			if(range <= 0f)
				return 0f
			val v = ((event.getAxisValue(axis) - min) / range).coerceIn(0f, 1f)
			return if(v < TRIGGER_NOISE) 0f else v
		}
	}

	val rightXAxis: Int
	val rightYAxis: Int
	val leftTriggers: List<Trigger>
	val rightTriggers: List<Trigger>
	val leftDeadZone: Float
	val rightDeadZone: Float

	init
	{
		fun range(axis: Int) = device?.getMotionRange(axis)
		fun has(axis: Int) = range(axis) != null
		fun pairHas(a: Int, b: Int) = has(a) && has(b)
		// a pair whose resting value is its minimum (never negative) is a pair of triggers
		fun pairIsTriggers(a: Int, b: Int) = (range(a)?.min ?: -1f) >= 0f && (range(b)?.min ?: -1f) >= 0f

		val hasZ = pairHas(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
		val hasRxy = pairHas(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
		val dedicated = (has(MotionEvent.AXIS_LTRIGGER) || has(MotionEvent.AXIS_BRAKE))
				&& (has(MotionEvent.AXIS_RTRIGGER) || has(MotionEvent.AXIS_GAS))

		val stickOnRxy = when
		{
			hasZ && hasRxy ->
				when
				{
					pairIsTriggers(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ) -> true
					pairIsTriggers(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY) -> false
					else -> false // DualShock style: stick on Z/RZ, triggers mirrored on RX/RY
				}
			hasRxy -> true
			else -> false
		}
		rightXAxis = if(stickOnRxy) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
		rightYAxis = if(stickOnRxy) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ

		val left = mutableListOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
		val right = mutableListOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
		// fallback pair: the one the right stick does not use
		if(stickOnRxy)
		{
			if(hasZ)
			{
				left.add(MotionEvent.AXIS_Z)
				right.add(MotionEvent.AXIS_RZ)
			}
		}
		else if(hasRxy && !dedicated)
		{
			left.add(MotionEvent.AXIS_RX)
			right.add(MotionEvent.AXIS_RY)
		}
		fun triggers(axes: List<Int>) = axes.mapNotNull { axis -> range(axis)?.let { Trigger(axis, it.min, it.max) } }
		leftTriggers = triggers(left)
		rightTriggers = triggers(right)

		fun flat(a: Int, b: Int) = max(range(a)?.flat ?: 0f, range(b)?.flat ?: 0f)
		leftDeadZone = max(MIN_DEAD_ZONE, flat(MotionEvent.AXIS_X, MotionEvent.AXIS_Y))
		rightDeadZone = max(MIN_DEAD_ZONE, flat(rightXAxis, rightYAxis))
	}

	fun readTrigger(triggers: List<Trigger>, event: MotionEvent): Float
		= triggers.fold(0f) { acc, t -> max(acc, t.read(event)) }

	companion object
	{
		const val MIN_DEAD_ZONE = 0.10f
		private const val TRIGGER_NOISE = 0.03f

		/**
		 * Radial dead zone with rescale, so the output still reaches 1.0 at full deflection.
		 * Writes the result into [out] (x at 0, y at 1).
		 */
		fun radial(x: Float, y: Float, deadZone: Float, out: FloatArray)
		{
			val mag = hypot(x, y)
			if(mag < deadZone || mag == 0f)
			{
				out[0] = 0f
				out[1] = 0f
				return
			}
			val scaled = min(1f, (mag - deadZone) / (1f - deadZone))
			out[0] = (x / mag * scaled).coerceIn(-1f, 1f)
			out[1] = (y / mag * scaled).coerceIn(-1f, 1f)
		}
	}
}
