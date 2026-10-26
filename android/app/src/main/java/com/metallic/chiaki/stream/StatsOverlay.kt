// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.StreamStats
import java.util.Locale

enum class StreamQuality(val color: Int)
{
	GOOD(0xFF4AE3A0.toInt()),
	FAIR(0xFFFFC54D.toInt()),
	BAD(0xFFFF6B7A.toInt())
}

enum class StreamDiagnosis { OK, NETWORK, DECODER }

/** Pure rules behind the stats pill, kept apart from the views. */
object StreamHealth
{
	/**
	 * Green: near the target frame rate and rtt under 30 ms. Red: under half the target or rtt over 60 ms.
	 * The reference values are 55 and 30 fps for a 60 fps target, scaled for a 30 fps target.
	 */
	fun quality(stats: StreamStats, targetFps: Int): StreamQuality
	{
		val target = if(targetFps > 0) targetFps.toFloat() else 60f
		val good = target * 55f / 60f
		val bad = target * 30f / 60f
		return when
		{
			stats.fps < bad || stats.rttMs > 60f -> StreamQuality.BAD
			stats.fps >= good && stats.rttMs < 30f -> StreamQuality.GOOD
			else -> StreamQuality.FAIR
		}
	}

	fun diagnosis(stats: StreamStats): StreamDiagnosis = when
	{
		stats.packetLossPercent > 2f || stats.rttMs > 60f -> StreamDiagnosis.NETWORK
		stats.decodeMs > 12f -> StreamDiagnosis.DECODER
		else -> StreamDiagnosis.OK
	}
}

/**
 * Top-right pill refreshed every 500 ms from the session statistics, only while enabled and started.
 */
class StatsOverlay(
	private val binding: ActivityStreamBinding,
	private val targetFps: Int,
	private val statsProvider: () -> StreamStats?)
{
	companion object
	{
		private const val REFRESH_MS = 500L
	}

	private val handler = Handler(Looper.getMainLooper())
	private val context get() = binding.root.context
	private var started = false

	var enabled = false
		set(value)
		{
			field = value
			sync()
		}

	private val refresh = object: Runnable {
		override fun run()
		{
			render(statsProvider())
			handler.postDelayed(this, REFRESH_MS)
		}
	}

	fun start()
	{
		started = true
		sync()
	}

	fun stop()
	{
		started = false
		sync()
	}

	private fun sync()
	{
		handler.removeCallbacks(refresh)
		if(enabled && started)
			refresh.run()
		else
			binding.statsPill.visibility = View.GONE
	}

	private fun render(stats: StreamStats?)
	{
		if(stats == null)
		{
			binding.statsPill.visibility = View.GONE
			return
		}
		fun num(v: Float, decimals: Int) = String.format(Locale.getDefault(), "%.${decimals}f", v)
		binding.statsText.text = context.getString(
			R.string.stream_stats_format,
			num(stats.fps, 0), num(stats.bitrateMbps, 1), num(stats.rttMs, 0), num(stats.decodeMs, 1), num(stats.packetLossPercent, 1))
		binding.statsDiagnosis.setText(when(StreamHealth.diagnosis(stats))
		{
			StreamDiagnosis.OK -> R.string.stream_stats_diag_ok
			StreamDiagnosis.NETWORK -> R.string.stream_stats_diag_network
			StreamDiagnosis.DECODER -> R.string.stream_stats_diag_decoder
		})
		(binding.statsQualityDot.background.mutate() as? GradientDrawable)
			?.setColor(StreamHealth.quality(stats, targetFps).color)
		binding.statsPill.visibility = View.VISIBLE
	}
}
