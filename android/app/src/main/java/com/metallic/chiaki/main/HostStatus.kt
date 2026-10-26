// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.lib.DiscoveryHost

/** What a console card tells the player: a state (text, icon and colour) and what OK will do. */
enum class HostStatus(
	@StringRes val label: Int,
	@StringRes val action: Int,
	@DrawableRes val icon: Int,
	@ColorRes val color: Int)
{
	READY(R.string.host_state_ready, R.string.host_action_play, R.drawable.ic_check_circle, R.color.state_ready),
	STANDBY(R.string.host_state_standby, R.string.host_action_wakeup_and_play, R.drawable.ic_moon, R.color.state_standby),
	REGISTERED(R.string.host_state_registered, R.string.host_action_try_connect, R.drawable.ic_link, R.color.state_registered),
	UNREGISTERED(R.string.host_state_unregistered, R.string.host_action_register, R.drawable.ic_plus_circle, R.color.state_unregistered)
}

val DisplayHost.status: HostStatus
	get()
	{
		if(!isRegistered)
			return HostStatus.UNREGISTERED
		if(this is DiscoveredDisplayHost)
		{
			return when(discoveredHost.state)
			{
				DiscoveryHost.State.READY -> HostStatus.READY
				DiscoveryHost.State.STANDBY -> HostStatus.STANDBY
				else -> HostStatus.REGISTERED
			}
		}
		return HostStatus.REGISTERED
	}
