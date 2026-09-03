package com.mobileapp.charlestunnel

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress

data class TunnelConfig(
    val host: String,
    val port: Int,
) {
    val endpoint: String
        get() = "${if (host.contains(':')) "[$host]" else host}:$port"

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8889

        fun parse(endpointInput: String): ConfigValidation {
            val endpoint = endpointInput.trim()
            if (endpoint.isEmpty()) return ConfigValidation.Invalid(ConfigError.INVALID_ENDPOINT)

            if (endpoint.startsWith('[')) {
                val closingBracket = endpoint.indexOf(']')
                if (
                    closingBracket <= 1 ||
                    closingBracket + 1 >= endpoint.length ||
                    endpoint[closingBracket + 1] != ':'
                ) {
                    return ConfigValidation.Invalid(ConfigError.INVALID_ENDPOINT)
                }
                return parse(
                    endpoint.substring(1, closingBracket),
                    endpoint.substring(closingBracket + 2),
                )
            }

            val separator = endpoint.lastIndexOf(':')
            if (separator <= 0 || separator == endpoint.lastIndex || endpoint.substring(0, separator).contains(':')) {
                return ConfigValidation.Invalid(ConfigError.INVALID_ENDPOINT)
            }
            return parse(endpoint.substring(0, separator), endpoint.substring(separator + 1))
        }

        fun parse(hostInput: String, portInput: String): ConfigValidation {
            val host = normalizeHost(hostInput) ?: return ConfigValidation.Invalid(ConfigError.INVALID_HOST)
            val port = portInput.trim().toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: return ConfigValidation.Invalid(ConfigError.INVALID_PORT)
            return ConfigValidation.Valid(TunnelConfig(host, port))
        }

        private fun normalizeHost(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.length > 253 || trimmed.any { it.isWhitespace() || it.isISOControl() }) {
                return null
            }

            val unwrapped = if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
            if (unwrapped.isEmpty()) return null

            if (unwrapped.contains(':')) {
                return normalizeIpv6(unwrapped)
            }

            if (unwrapped.all { it.isDigit() || it == '.' }) {
                return normalizeIpv4(unwrapped)
            }

            val ascii = runCatching { IDN.toASCII(unwrapped, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
                ?: return null
            if (ascii.length > 253) return null
            val canonical = ascii.removeSuffix(".").lowercase()
            if (canonical.isEmpty()) return null
            val labels = canonical.split('.')
            if (labels.any { label ->
                    label.isEmpty() ||
                        label.length > 63 ||
                        !label.first().isLetterOrDigit() ||
                        !label.last().isLetterOrDigit() ||
                        label.any { !it.isLetterOrDigit() && it != '-' }
                }
            ) {
                return null
            }
            return canonical
        }

        private fun normalizeIpv4(host: String): String? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val numbers = parts.map { part ->
                if (part.isEmpty() || part.length > 3 || (part.length > 1 && part.startsWith('0'))) return null
                part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            }
            return numbers.joinToString(".")
        }

        private fun normalizeIpv6(host: String): String? {
            if (host.contains('%')) return null
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
            return if (address is Inet6Address) host.lowercase() else null
        }
    }
}

enum class ConfigError {
    INVALID_ENDPOINT,
    INVALID_HOST,
    INVALID_PORT,
}

sealed interface ConfigValidation {
    data class Valid(val config: TunnelConfig) : ConfigValidation
    data class Invalid(val error: ConfigError) : ConfigValidation
}
