// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Keeps the Wi-Fi radio in its lowest latency mode for the duration of a stream.
 * Does nothing on Ethernet (Shield TV wired) or when the manifest lacks WAKE_LOCK.
 */
class LowLatencyWifiLock(context: Context)
{
	private val appContext = context.applicationContext
	private var lock: WifiManager.WifiLock? = null

	private fun onWifi(): Boolean
	{
		val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
		return try
		{
			val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
			caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
		}
		catch(e: SecurityException)
		{
			false
		}
	}

	@Suppress("DEPRECATION")
	fun acquire()
	{
		if(lock?.isHeld == true || !onWifi())
			return
		val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
		val mode = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			WifiManager.WIFI_MODE_FULL_LOW_LATENCY
		else
			WifiManager.WIFI_MODE_FULL_HIGH_PERF
		try
		{
			lock = wifi.createWifiLock(mode, "chiaki:stream").apply {
				setReferenceCounted(false)
				acquire()
			}
		}
		catch(e: SecurityException)
		{
			Log.w("LowLatencyWifiLock", "WAKE_LOCK permission missing: ${e.message}")
		}
	}

	fun release()
	{
		try
		{
			lock?.takeIf { it.isHeld }?.release()
		}
		catch(e: RuntimeException)
		{
			Log.w("LowLatencyWifiLock", "release failed: ${e.message}")
		}
		lock = null
	}
}
