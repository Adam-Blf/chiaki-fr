// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.psn

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.R
import com.metallic.chiaki.common.TvActivity
import com.metallic.chiaki.databinding.ActivityPsnLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Signs in to the PSN inside a WebView and returns the Remote Play account id (and the online id)
 * to the registration screen. Nothing the user types is kept: cookies, cache and web storage are
 * wiped when the screen opens and when it closes, and tokens only live in [PsnAuth.fetchAccount].
 */
class PsnLoginActivity: TvActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "psn_account_id"
		const val EXTRA_ONLINE_ID = "psn_online_id"
	}

	private lateinit var binding: ActivityPsnLoginBinding
	private var redirectHandled = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityPsnLoginBinding.inflate(layoutInflater)
		setContentView(binding.root)

		onBackPressedDispatcher.addCallback(this) {
			if(binding.webView.visibility == View.VISIBLE && binding.webView.canGoBack())
				binding.webView.goBack()
			else
			{
				isEnabled = false
				onBackPressedDispatcher.onBackPressed()
			}
		}

		setupWebView()
		binding.retryButton.setOnClickListener { startLogin() }
		startLogin()
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Suppress("DEPRECATION")
	private fun setupWebView()
	{
		binding.webView.settings.also {
			it.javaScriptEnabled = true
			it.domStorageEnabled = true
			it.savePassword = false
			it.saveFormData = false
			it.allowFileAccess = false
			it.allowContentAccess = false
			it.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
			it.cacheMode = WebSettings.LOAD_NO_CACHE
		}
		CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
		binding.webView.webViewClient = object: WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean
				= handleRedirect(request.url.toString())

			override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?)
			{
				if(url != null && handleRedirect(url))
				{
					view.stopLoading()
					return
				}
				binding.progressBar.visibility = View.VISIBLE
			}

			override fun onPageFinished(view: WebView, url: String?)
			{
				if(binding.webView.visibility == View.VISIBLE)
					binding.progressBar.visibility = View.GONE
			}

			override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError)
			{
				if(request.isForMainFrame && !redirectHandled)
					showError(R.string.psn_login_error_network)
			}

			override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError)
			{
				handler.cancel()
				showError(R.string.psn_login_error_network)
			}
		}
	}

	private fun wipeWebData()
	{
		CookieManager.getInstance().removeAllCookies(null)
		CookieManager.getInstance().flush()
		WebStorage.getInstance().deleteAllData()
		binding.webView.clearCache(true)
		binding.webView.clearFormData()
		binding.webView.clearHistory()
	}

	private fun startLogin()
	{
		redirectHandled = false
		binding.messageLayout.visibility = View.GONE
		binding.webView.visibility = View.VISIBLE
		binding.progressBar.visibility = View.VISIBLE
		wipeWebData()
		binding.webView.loadUrl(PsnAuth.AUTHORIZE_URL)
		binding.webView.requestFocus()
	}

	/** True when [url] is the Remote Play redirect: the sign-in is over and the code is exchanged. */
	private fun handleRedirect(url: String): Boolean
	{
		if(!url.startsWith(PsnAuth.REDIRECT_URI))
			return false
		if(redirectHandled)
			return true
		redirectHandled = true

		val code = Uri.parse(url).getQueryParameter("code")
		if(code.isNullOrEmpty())
		{
			showError(R.string.psn_login_error_no_code)
			return true
		}

		binding.webView.visibility = View.INVISIBLE
		binding.progressBar.visibility = View.VISIBLE
		binding.messageLayout.visibility = View.GONE
		lifecycleScope.launch {
			val account = withContext(Dispatchers.IO) {
				try { PsnAuth.fetchAccount(code) }
				catch(_: IOException) { null }
			}
			if(account == null)
			{
				showError(R.string.psn_login_error_exchange)
				return@launch
			}
			setResult(RESULT_OK, Intent().also {
				it.putExtra(EXTRA_ACCOUNT_ID, account.accountId)
				it.putExtra(EXTRA_ONLINE_ID, account.onlineId)
			})
			finish()
		}
		return true
	}

	private fun showError(@StringRes message: Int)
	{
		binding.webView.visibility = View.INVISIBLE
		binding.progressBar.visibility = View.GONE
		binding.messageTextView.setText(message)
		binding.messageLayout.visibility = View.VISIBLE
		binding.retryButton.requestFocus()
	}

	override fun onDestroy()
	{
		wipeWebData()
		(binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)
		binding.webView.destroy()
		super.onDestroy()
	}
}
