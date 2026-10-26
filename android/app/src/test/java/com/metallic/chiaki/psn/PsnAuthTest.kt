// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.psn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PsnAuthTest
{
	@Test
	fun accountIdIsLittleEndian()
	{
		assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0), PsnAuth.accountIdBytes("1"))
		assertArrayEquals(byteArrayOf(2, 1, 0, 0, 0, 0, 0, 0), PsnAuth.accountIdBytes("258"))
		assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x80.toByte()), PsnAuth.accountIdBytes("9223372036854775808"))
	}

	@Test
	fun accountIdAcceptsFullUnsignedRange()
	{
		assertArrayEquals(ByteArray(8) { 0xFF.toByte() }, PsnAuth.accountIdBytes("18446744073709551615"))
	}

	@Test
	fun accountIdRejectsGarbage()
	{
		assertThrows(PsnAuth.PsnAuthException::class.java) { PsnAuth.accountIdBytes("") }
		assertThrows(PsnAuth.PsnAuthException::class.java) { PsnAuth.accountIdBytes("abc") }
		assertThrows(PsnAuth.PsnAuthException::class.java) { PsnAuth.accountIdBytes("-1") }
		assertThrows(PsnAuth.PsnAuthException::class.java) { PsnAuth.accountIdBytes("18446744073709551616") }
	}
}
