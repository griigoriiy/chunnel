package com.mobileapp.charlestunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    @Test
    fun acceptsDefaultEndpoint() {
        assertEquals(
            ConfigValidation.Valid(TunnelConfig("127.0.0.1", 8889)),
            TunnelConfig.parse("127.0.0.1:8889"),
        )
    }

    @Test
    fun normalizesInternationalHostname() {
        assertEquals(
            ConfigValidation.Valid(TunnelConfig("xn--e1afmkfd.xn--p1ai", 1080)),
            TunnelConfig.parse("пример.рф:1080"),
        )
    }

    @Test
    fun acceptsBracketedIpv6() {
        assertEquals(
            ConfigValidation.Valid(TunnelConfig("2001:db8::1", 1080)),
            TunnelConfig.parse("[2001:db8::1]:1080"),
        )
    }

    @Test
    fun formatsIpv6WithBrackets() {
        assertEquals("[2001:db8::1]:1080", TunnelConfig("2001:db8::1", 1080).endpoint)
    }

    @Test
    fun rejectsUnbracketedIpv6Endpoint() {
        val result = TunnelConfig.parse("2001:db8::1:1080")
        assertTrue(result == ConfigValidation.Invalid(ConfigError.INVALID_ENDPOINT))
    }

    @Test
    fun rejectsInvalidPort() {
        val result = TunnelConfig.parse("localhost:65536")
        assertTrue(result == ConfigValidation.Invalid(ConfigError.INVALID_PORT))
    }

    @Test
    fun rejectsYamlMetacharactersInHost() {
        val result = TunnelConfig.parse("host'\nlog-level: debug:1080")
        assertTrue(result is ConfigValidation.Invalid)
    }
}
