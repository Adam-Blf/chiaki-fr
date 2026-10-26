// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.*
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.session.StreamSession
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.session.StreamInput

class StreamViewModel(val application: Application, val connectInfo: ConnectInfo): ViewModel()
{
	val preferences = Preferences(application)
	val logManager = LogManager(application)

	private var _session: StreamSession? = null
	val input = StreamInput(application, preferences)
	val session = StreamSession(connectInfo, logManager, preferences.logVerbose, input)

	/** False on Android TV boxes: no on-screen controls, no touch switches. */
	val hasTouchscreen = application.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

	private val _gamepadUsed = MutableLiveData(false)
	val gamepadUsed: LiveData<Boolean> get() = _gamepadUsed

	private var _onScreenControlsEnabled = MutableLiveData<Boolean>(preferences.onScreenControlsEnabled)
	val onScreenControlsEnabled: LiveData<Boolean> get() = _onScreenControlsEnabled

	private var _touchpadOnlyEnabled = MutableLiveData<Boolean>(preferences.touchpadOnlyEnabled)
	val touchpadOnlyEnabled: LiveData<Boolean> get() = _touchpadOnlyEnabled

	/** What the touch fragments actually show: the preference, on a touch device, while no gamepad is in use. */
	val onScreenControlsVisible: LiveData<Boolean> = visibleWhenTouching(_onScreenControlsEnabled)
	val touchpadOnlyVisible: LiveData<Boolean> = visibleWhenTouching(_touchpadOnlyEnabled)

	init
	{
		session.matchDisplayFrameRate = preferences.lowLatency
	}

	private fun visibleWhenTouching(enabled: LiveData<Boolean>): LiveData<Boolean> = MediatorLiveData<Boolean>().apply {
		fun update() { value = enabled.value == true && hasTouchscreen && _gamepadUsed.value != true }
		addSource(enabled) { update() }
		addSource(_gamepadUsed) { update() }
	}

	/** A gamepad was used (true) or the touchscreen was touched (false). */
	fun setGamepadUsed(used: Boolean)
	{
		if(_gamepadUsed.value != used)
			_gamepadUsed.value = used
	}

	override fun onCleared()
	{
		super.onCleared()
		_session?.shutdown()
	}

	fun setOnScreenControlsEnabled(enabled: Boolean)
	{
		preferences.onScreenControlsEnabled = enabled
		_onScreenControlsEnabled.value = enabled
	}

	fun setTouchpadOnlyEnabled(enabled: Boolean)
	{
		preferences.touchpadOnlyEnabled = enabled
		_touchpadOnlyEnabled.value = enabled
	}
}