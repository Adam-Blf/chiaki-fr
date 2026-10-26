// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import androidx.lifecycle.LifecycleOwner
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState
import kotlin.math.abs

/** Navigation commands sent to the in-stream menu while it is open. */
enum class MenuAction { UP, DOWN, LEFT, RIGHT, OK, CLOSE }

/**
 * Turns Android key and motion events into a PS controller state.
 *
 * Two kinds of devices are told apart by their input source:
 * - gamepads (SOURCE_GAMEPAD / SOURCE_JOYSTICK): full mapping, one active device at a time
 * - remotes and keyboards: D-pad, OK, play/pause and menu keys only
 */
class StreamInput(val context: Context, val preferences: Preferences)
{
	companion object
	{
		private const val PULSE_MS = 80L
		private const val CLAIM_STICK = 0.35f
		private const val CLAIM_TRIGGER = 0.30f
		private const val NAV_STICK = 0.6f
		private const val NAV_FIRST_REPEAT_MS = 400L
		private const val NAV_REPEAT_MS = 180L
		private const val DUPLICATE_NAV_MS = 120L
		private const val HAT_THRESHOLD = 0.5f
		private val DPAD_MASK = ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_LEFT or
				ControllerState.BUTTON_DPAD_DOWN or ControllerState.BUTTON_DPAD_UP

		private fun isGamepadSource(source: Int)
			= (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
				(source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
	}

	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	/** A gamepad produced meaningful input (used to hide the touch controls). */
	var gamepadActivity: (() -> Unit)? = null

	/** The remote Back key was pressed. Never forwarded to the console, never closes the activity. */
	var exitRequested: (() -> Unit)? = null

	/** The user held Options (2 s) or Select + Start (1.5 s) on a gamepad. */
	var menuRequested: (() -> Unit)? = null

	/** Receives navigation commands while [menuOpen]. */
	var menuAction: ((MenuAction) -> Unit)? = null

	/** While true nothing is forwarded to the console. Opening it releases every button. */
	var menuOpen = false
		set(value)
		{
			if(field == value)
				return
			field = value
			navDirection = null
			if(value)
				releaseAll()
		}

	/** Id of the gamepad currently driving the console, -1 if none. */
	var activeDeviceId = -1
		private set

	val activeDevice: InputDevice? get() = if(activeDeviceId >= 0) InputDevice.getDevice(activeDeviceId) else null

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState or remoteControllerState
		controllerState.buttons = controllerState.buttons or pulseMask

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

	private val handler = Handler(Looper.getMainLooper())
	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from gamepad KeyEvents
	private val motionControllerState = ControllerState() // from gamepad MotionEvents
	private val remoteControllerState = ControllerState() // from remote / keyboard KeyEvents
	private val motionSensors = MotionSensors(context, sensorControllerState) { controllerStateUpdated() }
	private var pulseMask = 0U
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			if(!menuOpen)
				controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon
	private val layouts = mutableMapOf<Int, GamepadLayout>()
	private val stickOut = FloatArray(2)

	private val systemButtons = SystemButtons(
		handler,
		setButtons = { set, clear ->
			keyControllerState.buttons = (keyControllerState.buttons or set) and clear.inv()
			controllerStateUpdated()
		},
		pulse = { pulse(it) },
		menuRequested = { menuRequested?.invoke() }
	)

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionSensors)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	/** Zeroes every button, trigger and stick, and cancels pending hold timers. */
	fun releaseAll()
	{
		resetGamepadState()
		remoteControllerState.buttons = 0U
		pulseMask = 0U
		handler.removeCallbacksAndMessages(null)
		touchControllerState = ControllerState()
		controllerStateUpdated()
	}

	private fun resetGamepadState()
	{
		systemButtons.reset()
		clearAxesAndButtons(keyControllerState)
		clearAxesAndButtons(motionControllerState)
	}

	private fun clearAxesAndButtons(s: ControllerState)
	{
		s.buttons = 0U
		s.l2State = 0U
		s.r2State = 0U
		s.leftX = 0
		s.leftY = 0
		s.rightX = 0
		s.rightY = 0
	}

	private fun pulse(mask: UInt)
	{
		pulseMask = pulseMask or mask
		controllerStateUpdated()
		handler.postDelayed({
			pulseMask = pulseMask and mask.inv()
			controllerStateUpdated()
		}, PULSE_MS)
	}

	/**
	 * Single active device: the first one that sends something meaningful claims the console,
	 * the others are ignored until they send something meaningful themselves (then states reset).
	 */
	private fun acceptDevice(deviceId: Int, meaningful: Boolean): Boolean
	{
		if(deviceId == activeDeviceId)
			return true
		if(!meaningful)
			return false
		resetGamepadState()
		layouts.clear()
		activeDeviceId = deviceId
		controllerStateUpdated()
		return true
	}

	// ---- keys ----

	private var lastDpadKeyNavTime = 0L

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false
		val down = event.action == KeyEvent.ACTION_DOWN
		val gamepad = isGamepadSource(event.source) || KeyEvent.isGamepadButton(event.keyCode)

		if(event.keyCode == KeyEvent.KEYCODE_BACK && !gamepad)
		{
			// consume down and up: the activity must never see a Back and exit by accident
			if(down && event.repeatCount == 0)
				exitRequested?.invoke()
			return true
		}
		if(menuOpen)
			return menuKey(event, down, gamepad)
		return if(gamepad) gamepadKey(event, down) else remoteKey(event.keyCode, down)
	}

	private fun menuKey(event: KeyEvent, down: Boolean, gamepad: Boolean): Boolean
	{
		val action = when(event.keyCode)
		{
			KeyEvent.KEYCODE_DPAD_UP -> MenuAction.UP
			KeyEvent.KEYCODE_DPAD_DOWN -> MenuAction.DOWN
			KeyEvent.KEYCODE_DPAD_LEFT -> MenuAction.LEFT
			KeyEvent.KEYCODE_DPAD_RIGHT -> MenuAction.RIGHT
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
			KeyEvent.KEYCODE_BUTTON_A -> MenuAction.OK
			KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuAction.CLOSE
			else -> null
		}
		if(action == null)
			return gamepad
		if(!down)
			return true
		if(gamepad)
		{
			if(!acceptDevice(event.deviceId, true))
				return true
			gamepadActivity?.invoke()
		}
		val navigation = action == MenuAction.UP || action == MenuAction.DOWN ||
				action == MenuAction.LEFT || action == MenuAction.RIGHT
		if(!navigation && event.repeatCount > 0)
			return true
		val now = SystemClock.uptimeMillis()
		if(navigation)
		{
			if(now - lastHatNavTime < DUPLICATE_NAV_MS)
				return true
			lastDpadKeyNavTime = now
		}
		menuAction?.invoke(action)
		return true
	}

	private fun remoteKey(code: Int, down: Boolean): Boolean
	{
		val mask = when(code)
		{
			KeyEvent.KEYCODE_DPAD_LEFT -> ControllerState.BUTTON_DPAD_LEFT
			KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
			KeyEvent.KEYCODE_DPAD_UP -> ControllerState.BUTTON_DPAD_UP
			KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ControllerState.BUTTON_PS
			KeyEvent.KEYCODE_MENU -> ControllerState.BUTTON_OPTIONS
			else -> return false
		}
		remoteControllerState.buttons = if(down)
			remoteControllerState.buttons or mask
		else
			remoteControllerState.buttons and mask.inv()
		controllerStateUpdated()
		return true
	}

	private fun gamepadKey(event: KeyEvent, down: Boolean): Boolean
	{
		val code = event.keyCode
		val mask: UInt = when(code)
		{
			KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
			KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_START -> 0U
			KeyEvent.KEYCODE_DPAD_LEFT -> ControllerState.BUTTON_DPAD_LEFT
			KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
			KeyEvent.KEYCODE_DPAD_UP -> ControllerState.BUTTON_DPAD_UP
			KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
			KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
			KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
			KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
			KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
			KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
			KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
			KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
			KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
			else -> return false
		}
		if(!acceptDevice(event.deviceId, down))
			return true
		if(down)
			gamepadActivity?.invoke()

		when(code)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> keyControllerState.l2State = if(down) UByte.MAX_VALUE else 0U
			KeyEvent.KEYCODE_BUTTON_R2 -> keyControllerState.r2State = if(down) UByte.MAX_VALUE else 0U
			// Back on a gamepad is Select/Share, never an exit
			KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> systemButtons.onSelect(down)
			KeyEvent.KEYCODE_BUTTON_START -> systemButtons.onStart(down)
			else -> keyControllerState.buttons = if(down)
				keyControllerState.buttons or mask
			else
				keyControllerState.buttons and mask.inv()
		}
		controllerStateUpdated()
		return true
	}

	// ---- motion ----

	private var navDirection: MenuAction? = null
	private var navNextTime = 0L
	private var lastHatNavTime = 0L

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(!isGamepadSource(event.source) || event.action != MotionEvent.ACTION_MOVE)
			return false
		val layout = layouts.getOrPut(event.deviceId) { GamepadLayout(event.device) }

		val rawLeftX = event.getAxisValue(MotionEvent.AXIS_X)
		val rawLeftY = event.getAxisValue(MotionEvent.AXIS_Y)
		val rawRightX = event.getAxisValue(layout.rightXAxis)
		val rawRightY = event.getAxisValue(layout.rightYAxis)
		val l2 = layout.readTrigger(layout.leftTriggers, event)
		val r2 = layout.readTrigger(layout.rightTriggers, event)
		val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
		val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

		val meaningful = abs(rawLeftX) > CLAIM_STICK || abs(rawLeftY) > CLAIM_STICK ||
				abs(rawRightX) > CLAIM_STICK || abs(rawRightY) > CLAIM_STICK ||
				l2 > CLAIM_TRIGGER || r2 > CLAIM_TRIGGER || abs(hatX) > HAT_THRESHOLD || abs(hatY) > HAT_THRESHOLD
		if(!acceptDevice(event.deviceId, meaningful))
			return true
		if(meaningful)
			gamepadActivity?.invoke()

		if(menuOpen)
		{
			menuMotion(rawLeftX, rawLeftY, hatX, hatY, layout)
			return true
		}

		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		GamepadLayout.radial(rawLeftX, rawLeftY, layout.leftDeadZone, stickOut)
		motionControllerState.leftX = stickOut[0].signedAxis()
		motionControllerState.leftY = stickOut[1].signedAxis()
		GamepadLayout.radial(rawRightX, rawRightY, layout.rightDeadZone, stickOut)
		motionControllerState.rightX = stickOut[0].signedAxis()
		motionControllerState.rightY = stickOut[1].signedAxis()
		motionControllerState.l2State = l2.unsignedAxis()
		motionControllerState.r2State = r2.unsignedAxis()
		val dpadButtons =
			(if(hatX > HAT_THRESHOLD) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
					(if(hatX < -HAT_THRESHOLD) ControllerState.BUTTON_DPAD_LEFT else 0U) or
					(if(hatY > HAT_THRESHOLD) ControllerState.BUTTON_DPAD_DOWN else 0U) or
					(if(hatY < -HAT_THRESHOLD) ControllerState.BUTTON_DPAD_UP else 0U)
		motionControllerState.buttons = (motionControllerState.buttons and DPAD_MASK.inv()) or dpadButtons
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}

	/** Left stick or hat to menu navigation, edge triggered with a slow auto-repeat. */
	private fun menuMotion(rawX: Float, rawY: Float, hatX: Float, hatY: Float, layout: GamepadLayout)
	{
		GamepadLayout.radial(rawX, rawY, layout.leftDeadZone, stickOut)
		val hatActive = abs(hatX) > HAT_THRESHOLD || abs(hatY) > HAT_THRESHOLD
		val x = if(hatActive) hatX else stickOut[0]
		val y = if(hatActive) hatY else stickOut[1]
		val threshold = if(hatActive) HAT_THRESHOLD else NAV_STICK
		val direction = when
		{
			abs(x) >= abs(y) && x > threshold -> MenuAction.RIGHT
			abs(x) >= abs(y) && x < -threshold -> MenuAction.LEFT
			abs(y) > abs(x) && y > threshold -> MenuAction.DOWN
			abs(y) > abs(x) && y < -threshold -> MenuAction.UP
			else -> null
		}
		val now = SystemClock.uptimeMillis()
		when
		{
			direction == null -> navDirection = null
			direction != navDirection ->
			{
				navDirection = direction
				navNextTime = now + NAV_FIRST_REPEAT_MS
				fireNavigation(direction, now)
			}
			now >= navNextTime ->
			{
				navNextTime = now + NAV_REPEAT_MS
				fireNavigation(direction, now)
			}
		}
	}

	private fun fireNavigation(direction: MenuAction, now: Long)
	{
		// many pads send the same D-pad press as both a key and a hat movement
		if(now - lastDpadKeyNavTime < DUPLICATE_NAV_MS)
			return
		lastHatNavTime = now
		menuAction?.invoke(direction)
	}
}
