package com.mobileapp.charlestunnel.nativebridge

import hev.sockstun.TProxyService
import java.io.File
import java.io.IOException

object NativeTunnel {
    @Volatile
    private var result = 0

    @Synchronized
    fun start(configDirectory: File, host: String, port: Int, tunFileDescriptor: Int): Int {
        if (isRunning()) return ERROR_BUSY

        val configFile = configDirectory.resolve(CONFIG_FILE_NAME)
        return try {
            configFile.writeText(config(host, port), Charsets.UTF_8)
            if (TProxyService.TProxyStartService(configFile.absolutePath, tunFileDescriptor)) {
                result = 0
                0
            } else {
                markFailed(ERROR_NATIVE)
            }
        } catch (_: IOException) {
            markFailed(ERROR_IO)
        } catch (_: LinkageError) {
            markFailed(ERROR_NATIVE)
        }
    }

    @Synchronized
    fun stop() {
        try {
            result = if (TProxyService.TProxyStopService()) 0 else ERROR_NATIVE
        } catch (_: LinkageError) {
            result = ERROR_NATIVE
        }
    }

    fun isRunning(): Boolean = try {
        TProxyService.TProxyIsRunning()
    } catch (_: LinkageError) {
        result = ERROR_NATIVE
        false
    }

    @Synchronized
    fun stats(): LongArray {
        if (!isRunning()) return LongArray(STATS_SIZE)
        return try {
            TProxyService.TProxyGetStats().copyOf(STATS_SIZE)
        } catch (_: LinkageError) {
            result = ERROR_NATIVE
            LongArray(STATS_SIZE)
        }
    }

    fun lastResult(): Int = result

    private fun markFailed(error: Int): Int {
        result = error
        return error
    }

    private fun config(host: String, port: Int): String = """
        misc:
          task-stack-size: 86016
          tcp-buffer-size: 65536
          connect-timeout: 10000
          log-file: null
          log-level: error
        tunnel:
          mtu: 8500
          icmp: 'off'
        socks5:
          port: $port
          address: '$host'
          udp: 'tcp'
        mapdns:
          address: 198.18.0.2
          port: 53
          network: 240.0.0.0
          netmask: 240.0.0.0
          cache-size: 10000
    """.trimIndent() + "\n"

    private const val CONFIG_FILE_NAME = "tunnel.yml"
    private const val STATS_SIZE = 4
    private const val ERROR_NATIVE = -1
    private const val ERROR_IO = -5
    private const val ERROR_BUSY = -16
}
