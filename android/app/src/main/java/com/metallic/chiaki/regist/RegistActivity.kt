// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.Window
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.metallic.chiaki.R
import com.metallic.chiaki.common.TvActivity
import com.metallic.chiaki.common.ext.RevealActivity
import com.metallic.chiaki.databinding.ActivityRegistBinding
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import com.metallic.chiaki.psn.PsnLoginActivity
import java.lang.IllegalArgumentException

class RegistActivity: TvActivity(), RevealActivity
{
	companion object
	{
		const val EXTRA_HOST = "regist_host"
		const val EXTRA_BROADCAST = "regist_broadcast"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"

		private const val PIN_LENGTH = 8
	}

	private lateinit var viewModel: RegistViewModel
	private lateinit var binding: ActivityRegistBinding

	override val revealWindow: Window get() = window
	override val revealIntent: Intent get() = intent
	override val revealRootLayout: View get() = binding.rootLayout

	private val registLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if(it.resultCode == RESULT_OK)
			finish()
	}

	private val psnLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if(it.resultCode != RESULT_OK)
			return@registerForActivityResult
		val data = it.data ?: return@registerForActivityResult
		val accountId = data.getStringExtra(PsnLoginActivity.EXTRA_ACCOUNT_ID)
		val onlineId = data.getStringExtra(PsnLoginActivity.EXTRA_ONLINE_ID)
		applyPsnLogin(accountId, onlineId)
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityRegistBinding.inflate(layoutInflater)
		setContentView(binding.root)
		handleReveal()

		viewModel = ViewModelProvider(this).get(RegistViewModel::class.java)

		binding.hostEditText.setText(intent.getStringExtra(EXTRA_HOST) ?: "255.255.255.255")
		binding.broadcastCheckBox.isChecked = intent.getBooleanExtra(EXTRA_BROADCAST, true)

		binding.registButton.setOnClickListener { doRegist() }
		binding.psnLoginButton.setOnClickListener {
			psnLoginLauncher.launch(Intent(this, PsnLoginActivity::class.java))
		}
		// Typing on a TV is slow: the PSN sign-in button is where the focus starts
		binding.psnLoginButton.requestFocus()

		binding.ps4VersionRadioGroup.check(when(viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5) {
			RegistViewModel.ConsoleVersion.PS5 -> R.id.ps5RadioButton
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> R.id.ps4VersionGE8RadioButton
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> R.id.ps4VersionGE7RadioButton
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.id.ps4VersionLT7RadioButton
		})

		binding.ps4VersionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
			viewModel.ps4Version.value = when(checkedId)
			{
				R.id.ps5RadioButton -> RegistViewModel.ConsoleVersion.PS5
				R.id.ps4VersionGE8RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_8
				R.id.ps4VersionGE7RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_7
				R.id.ps4VersionLT7RadioButton -> RegistViewModel.ConsoleVersion.PS4_LT_7
				else -> RegistViewModel.ConsoleVersion.PS5
			}
		}

		viewModel.ps4Version.observe(this, Observer {
			binding.psnIdTextInputLayout.hint = getString(when(it!!)
			{
				RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.string.hint_regist_psn_online_id
				else -> R.string.hint_regist_psn_account_id
			})
			binding.pinHelpBeforeTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_before else R.string.regist_pin_instructions_ps4_before)
			binding.pinHelpNavigationTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_navigation else R.string.regist_pin_instructions_ps4_navigation)
		})
	}

	/** Fills the PSN field with what the console generation expects and confirms who is signed in. */
	private fun applyPsnLogin(accountId: String?, onlineId: String?)
	{
		val ps4Version = viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5
		val psnId = if(ps4Version == RegistViewModel.ConsoleVersion.PS4_LT_7) onlineId else accountId
		if(psnId.isNullOrEmpty())
			return
		binding.psnIdEditText.setText(psnId)
		binding.psnIdEditText.error = null
		binding.psnLoginStatusTextView.text = getString(R.string.psn_login_success, onlineId ?: getString(R.string.psn_login_unknown_user))
		binding.psnLoginStatusTextView.visibility = View.VISIBLE
		binding.pinEditText.requestFocus()
	}

	private fun doRegist()
	{
		val ps4Version = viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5

		val host = binding.hostEditText.text.toString().trim()
		val hostValid = host.isNotEmpty()
		val broadcast = binding.broadcastCheckBox.isChecked

		val psnId = binding.psnIdEditText.text.toString().trim()
		val psnOnlineId: String? = if(ps4Version == RegistViewModel.ConsoleVersion.PS4_LT_7) psnId else null
		val psnAccountId: ByteArray? =
			if(ps4Version != RegistViewModel.ConsoleVersion.PS4_LT_7)
				try { Base64.decode(psnId, Base64.DEFAULT) } catch(e: IllegalArgumentException) { null }
			else
				null
		val psnIdValid = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> psnOnlineId?.isNotEmpty() ?: false
			else -> psnAccountId != null && psnAccountId.size == RegistInfo.ACCOUNT_ID_SIZE
		}


		val pin = binding.pinEditText.text.toString()
		val pinValid = pin.length == PIN_LENGTH

		binding.hostEditText.error = if(!hostValid) getString(R.string.entered_host_invalid) else null
		binding.psnIdEditText.error =
			if(!psnIdValid)
				getString(when(ps4Version)
				{
					RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.string.regist_psn_online_id_invalid
					else -> R.string.regist_psn_account_id_invalid
				})
			else
				null
		binding.pinEditText.error = if(!pinValid) getString(R.string.regist_pin_invalid, PIN_LENGTH) else null

		if(!hostValid || !psnIdValid || !pinValid)
			return

		val target = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS5 -> Target.PS5_1
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> Target.PS4_10
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> Target.PS4_9
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> Target.PS4_8
		}

		val registInfo = RegistInfo(target, host, broadcast, psnOnlineId, psnAccountId, pin.toInt())

		Intent(this, RegistExecuteActivity::class.java).also {
			it.putExtra(RegistExecuteActivity.EXTRA_REGIST_INFO, registInfo)
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				it.putExtra(RegistExecuteActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0L))
			registLauncher.launch(it)
		}
	}
}
