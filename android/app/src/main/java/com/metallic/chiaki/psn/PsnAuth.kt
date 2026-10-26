// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.psn

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PSN sign-in for the Remote Play registration, the same flow as scripts/psn-account-id.py:
 * OAuth code -> access token -> user_id -> Remote Play account id.
 *
 * The client id and secret are Sony's public Remote Play client credentials, also published
 * in Chiaki's own script. Tokens are only kept in local variables: never logged, never stored.
 */
object PsnAuth
{
	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"

	const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"

	const val AUTHORIZE_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
		"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID" +
		"&redirect_uri=$REDIRECT_URI&scope=psn:clientapp&request_locale=fr_FR&ui=pr&service_logo=ps" +
		"&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal"

	private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
	private const val TIMEOUT_MS = 15000
	private const val ACCOUNT_ID_SIZE = 8

	class PsnAuthException(message: String, cause: Throwable? = null): IOException(message, cause)

	/** [accountId] is the Base64 form expected by the registration screen. */
	data class PsnAccount(val accountId: String, val onlineId: String?)

	/** The Remote Play account id is the PSN user id as a little-endian unsigned 64-bit integer. */
	fun accountIdBytes(userId: String): ByteArray
	{
		val value = userId.trim().toULongOrNull() ?: throw PsnAuthException("Invalid user_id")
		return ByteArray(ACCOUNT_ID_SIZE) { i -> (value shr (8 * i)).toByte() }
	}

	/** Blocking: call from a background dispatcher. */
	@Throws(IOException::class)
	fun fetchAccount(code: String): PsnAccount
	{
		val basic = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(Charsets.US_ASCII), Base64.NO_WRAP)

		val body = "grant_type=authorization_code" +
			"&code=${URLEncoder.encode(code, "UTF-8")}" +
			"&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}"
		val token = request(TOKEN_URL, "POST", basic, body).optString("access_token")
		if(token.isEmpty())
			throw PsnAuthException("access_token is missing")

		val info = request("$TOKEN_URL/${URLEncoder.encode(token, "UTF-8")}", "GET", basic, null)
		val userId = info.optString("user_id")
		if(userId.isEmpty())
			throw PsnAuthException("user_id is missing")

		val accountId = Base64.encodeToString(accountIdBytes(userId), Base64.NO_WRAP)
		val onlineId = info.optString("online_id").ifEmpty { null }
		return PsnAccount(accountId, onlineId)
	}

	private fun request(url: String, method: String, authorization: String, body: String?): JSONObject
	{
		val connection = URL(url).openConnection() as HttpURLConnection
		try
		{
			connection.requestMethod = method
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			connection.setRequestProperty("Authorization", authorization)
			if(body != null)
			{
				connection.doOutput = true
				connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
				connection.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
			}
			val status = connection.responseCode
			if(status != HttpURLConnection.HTTP_OK)
				throw PsnAuthException("HTTP $status")
			val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
			return JSONObject(text)
		}
		catch(e: JSONException)
		{
			throw PsnAuthException("Unreadable response", e)
		}
		finally
		{
			connection.disconnect()
		}
	}
}
