package dev.glide.transport.lan.tls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTlsProviderTest {

    @Test
    fun usesBundledConscrypt_targetsOnlyApi26Through28() {
        assertFalse(LanTlsProvider.usesBundledConscrypt(0))
        assertTrue(LanTlsProvider.usesBundledConscrypt(26))
        assertTrue(LanTlsProvider.usesBundledConscrypt(27))
        assertTrue(LanTlsProvider.usesBundledConscrypt(28))
        assertFalse(LanTlsProvider.usesBundledConscrypt(29))
        assertFalse(LanTlsProvider.usesBundledConscrypt(33))
        assertFalse(LanTlsProvider.usesBundledConscrypt(37))
    }

    @Test
    fun createTls13Context_succeedsOnJvmWithoutNativeAndroidLibs() {
        val context = LanTlsProvider.createTls13Context(sdkInt = 0)
        assertNotNull(context)
    }
}
