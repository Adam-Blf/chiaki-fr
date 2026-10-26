// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.lib.ControllerState

/** Feeds the device accelerometer, gyroscope and rotation vector into [state] while resumed. */
internal class MotionSensors(
	private val context: Context,
	private val state: ControllerState,
	private val onChanged: () -> Unit): LifecycleObserver
{
	private val listener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					state.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					state.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					state.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					state.gyroX = event.values[1]
					state.gyroY = event.values[2]
					state.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					state.orientX = q[2]
					state.orientY = q[3]
					state.orientZ = q[1]
					state.orientW = q[0]
				}
				else -> return
			}
			onChanged()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
	fun onResume()
	{
		val samplingPeriodUs = 4000
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		listOfNotNull(
			sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
			sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
			sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
		).forEach {
			sensorManager.registerListener(listener, it, samplingPeriodUs)
		}
	}

	@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
	fun onPause()
	{
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		sensorManager.unregisterListener(listener)
	}
}
