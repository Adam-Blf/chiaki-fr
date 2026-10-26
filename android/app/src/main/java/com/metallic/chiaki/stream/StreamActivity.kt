// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.graphics.Matrix
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		/** Optional display name of the console for the connecting card, defaults to its host. */
		const val EXTRA_CONSOLE_NAME = "console_name"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat

	private lateinit var menu: StreamMenu
	private lateinit var statsOverlay: StatsOverlay
	private var controllerRumble: ControllerRumble? = null
	private val wifiLock by lazy { LowLatencyWifiLock(this) }

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)

		if(viewModel.preferences.lowLatency)
		{
			window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
				window.setPreferMinimalPostProcessing(true)
		}

		// no touchscreen (TV box): the touch switches have no use, the stream menu replaces them
		if(!viewModel.hasTouchscreen)
			binding.overlay.isGone = true

		WindowCompat.setDecorFitsSystemWindows(window, false)
		insetsController = WindowCompat.getInsetsController(window, window.decorView)
		insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
			val systemBars = insets.isVisible(WindowInsetsCompat.Type.systemBars())
			if(systemBars)
				showOverlay()
			else
				hideOverlay()
			insets
		}

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		setupStreamMenu()
		setupInputCallbacks()
		setupStatsOverlay()
		setupRumble()

		val consoleName = intent.getStringExtra(EXTRA_CONSOLE_NAME) ?: connectInfo.host
		binding.connectingTitle.text = getString(R.string.stream_connecting_title, consoleName)

		// safety net for predictive back: same behaviour as the Back key, never an accidental exit
		onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
			override fun handleOnBackPressed()
			{
				if(viewModel.session.state.value == StreamStateConnecting)
					finish()
				else
					menu.toggle()
			}
		})

		//viewModel.session.attachToTextureView(textureView)
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()
	}

	private fun setupStreamMenu()
	{
		menu = StreamMenu(
			binding,
			onOpenChanged = { open ->
				viewModel.input.menuOpen = open
				if(open)
					controllerRumble?.cancel()
			},
			onStatsChanged = { enabled ->
				viewModel.preferences.statsOverlayEnabled = enabled
				statsOverlay.enabled = enabled
			},
			onSleep = {
				viewModel.session.gotoBed()
				finish()
			},
			onQuit = { finish() })
		menu.statsEnabled = viewModel.preferences.statsOverlayEnabled
	}

	private fun setupInputCallbacks()
	{
		viewModel.input.gamepadActivity = { viewModel.setGamepadUsed(true) }
		viewModel.input.exitRequested = { menu.toggle() }
		viewModel.input.menuRequested = { menu.open() }
		viewModel.input.menuAction = { menu.onAction(it) }
	}

	private fun setupStatsOverlay()
	{
		statsOverlay = StatsOverlay(binding, viewModel.connectInfo.videoProfile.maxFPS) { viewModel.session.stats() }
		statsOverlay.enabled = viewModel.preferences.statsOverlayEnabled
	}

	private fun setupRumble()
	{
		if(!viewModel.preferences.rumbleEnabled)
			return
		val rumble = ControllerRumble(this)
		controllerRumble = rumble
		viewModel.session.rumbleState.observe(this, Observer {
			if(menu.isOpen)
				rumble.cancel()
			else
				rumble.rumble(viewModel.input.activeDevice, it.left.toInt(), it.right.toInt())
		})
	}

	private var controlsJob: Job? = null

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			controlsJob?.cancel()
			controlsJob = fragment.controllerState
				.onEach { viewModel.input.touchControllerState = it }
				.launchIn(lifecycleScope)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsVisible
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyVisible
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		if(viewModel.preferences.lowLatency)
			wifiLock.acquire()
		statsOverlay.start()
		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		viewModel.input.releaseAll()
		controllerRumble?.cancel()
		statsOverlay.stop()
		wifiLock.release()
		viewModel.session.pause()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		controlsJob?.cancel()
		if(isFinishing)
		{
			viewModel.input.gamepadActivity = null
			viewModel.input.exitRequested = null
			viewModel.input.menuRequested = null
			viewModel.input.menuAction = null
		}
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	private fun showOverlay()
	{
		if(!viewModel.hasTouchscreen || (::menu.isInitialized && menu.isOpen))
			return
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		insetsController.hide(WindowInsetsCompat.Type.systemBars())
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.connectingCard.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			else ->{}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(viewModel.session.state.value == StreamStateConnecting
			&& (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B))
		{
			// "Retour pour annuler": nothing to protect yet, so Back (or B) leaves at once
			if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
				finish()
			return true
		}
		return viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean
	{
		// the touch controls come back on the first touch, on touch devices only
		if(ev.actionMasked == MotionEvent.ACTION_DOWN && viewModel.hasTouchscreen && !menu.isOpen
			&& ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN))
			viewModel.setGamepadUsed(false)
		return super.dispatchTouchEvent(ev)
	}

	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}
