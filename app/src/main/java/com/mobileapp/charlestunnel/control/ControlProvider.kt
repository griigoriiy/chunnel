package com.mobileapp.charlestunnel.control

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.net.VpnService
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.mobileapp.charlestunnel.ConfigError
import com.mobileapp.charlestunnel.ConfigValidation
import com.mobileapp.charlestunnel.R
import com.mobileapp.charlestunnel.TunnelConfig
import com.mobileapp.charlestunnel.TunnelState
import com.mobileapp.charlestunnel.nativebridge.NativeTunnel
import com.mobileapp.charlestunnel.service.TunnelVpnService
import com.mobileapp.charlestunnel.store.PendingCommand
import com.mobileapp.charlestunnel.store.TunnelStore

class ControlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        handleCall(method, arg, extras)

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle =
        handleCall(method, arg, extras)

    private fun handleCall(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceAdbCaller()
        val token = Binder.clearCallingIdentity()
        return try {
            val appContext = requireNotNull(context).applicationContext
            val store = TunnelStore(appContext)
            when (method) {
                METHOD_START -> stageStart(store, arg, extras)
                METHOD_STOP -> requestStop(appContext, store)
                METHOD_STATUS -> status(store, VpnService.prepare(appContext) == null)
                else -> result(success = false, code = "unknown_method", message = method)
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun requestStop(appContext: Context, store: TunnelStore): Bundle {
        store.setState(TunnelState.STOPPING)
        val delivered = runCatching {
            appContext.startService(
                Intent(appContext, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_STOP),
            ) != null
        }.getOrDefault(false)
        if (delivered) {
            return result(success = true, code = "stop_requested")
        }

        val persisted = store.stage(PendingCommand.STOP)
        return result(
            success = persisted,
            code = if (persisted) "pending_user_action" else "storage_error",
        )
    }

    private fun stageStart(store: TunnelStore, arg: String?, extras: Bundle?): Bundle {
        val validation = when {
            arg != null -> TunnelConfig.parse(arg)
            extras?.containsKey(EXTRA_ENDPOINT) == true -> {
                TunnelConfig.parse(extras.getString(EXTRA_ENDPOINT).orEmpty())
            }

            else -> {
                val host = extras?.getString(EXTRA_SOCKS_HOST) ?: TunnelConfig.DEFAULT_HOST
                val port = if (extras?.containsKey(EXTRA_SOCKS_PORT) == true) {
                    extras.getInt(EXTRA_SOCKS_PORT).toString()
                } else {
                    TunnelConfig.DEFAULT_PORT.toString()
                }
                TunnelConfig.parse(host, port)
            }
        }
        return when (validation) {
            is ConfigValidation.Valid -> {
                store.saveConfig(validation.config)
                val persisted = store.stage(PendingCommand.START)
                result(success = persisted, code = if (persisted) "pending_user_action" else "storage_error")
            }

            is ConfigValidation.Invalid -> result(
                success = false,
                code = when (validation.error) {
                    ConfigError.INVALID_ENDPOINT -> "invalid_endpoint"
                    ConfigError.INVALID_HOST -> "invalid_host"
                    ConfigError.INVALID_PORT -> "invalid_port"
                },
            )
        }
    }

    private fun status(store: TunnelStore, permissionGranted: Boolean): Bundle {
        val nativeRunning = NativeTunnel.isRunning()
        if (!nativeRunning) {
            when (store.state()) {
                TunnelState.RUNNING -> store.setState(
                    TunnelState.ERROR,
                    requireNotNull(context).getString(R.string.tunnel_native_failed, NativeTunnel.lastResult()),
                )

                TunnelState.STOPPING -> store.setState(TunnelState.IDLE)
                TunnelState.IDLE, TunnelState.STARTING, TunnelState.ERROR -> Unit
            }
        }
        val config = store.config()
        val state = store.state()
        val stats = if (state == TunnelState.RUNNING) NativeTunnel.stats() else LongArray(4)
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, true)
            putString(KEY_STATE, state.wireName)
            putString(KEY_ENDPOINT, config.endpoint)
            putString(KEY_SOCKS_HOST, config.host)
            putInt(KEY_SOCKS_PORT, config.port)
            putBoolean(KEY_VPN_PERMISSION_GRANTED, permissionGranted)
            putBoolean(KEY_NATIVE_RUNNING, nativeRunning)
            putString(KEY_PENDING_COMMAND, store.pendingCommandName())
            putString(KEY_ERROR, store.lastError())
            putLong(KEY_TX_PACKETS, stats.getOrElse(0) { 0L })
            putLong(KEY_TX_BYTES, stats.getOrElse(1) { 0L })
            putLong(KEY_RX_PACKETS, stats.getOrElse(2) { 0L })
            putLong(KEY_RX_BYTES, stats.getOrElse(3) { 0L })
        }
    }

    private fun enforceAdbCaller() {
        val callerUid = Binder.getCallingUid()
        if (callerUid != Process.SHELL_UID && callerUid != Process.ROOT_UID) {
            throw SecurityException("Charles Tunnel control is available only to adb shell or root")
        }
    }

    private fun result(success: Boolean, code: String, message: String? = null) = Bundle().apply {
        putBoolean(KEY_SUCCESS, success)
        putString(KEY_CODE, code)
        putString(KEY_MESSAGE, message)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val METHOD_START = "start"
        const val METHOD_STOP = "stop"
        const val METHOD_STATUS = "status"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SOCKS_HOST = "socks_host"
        const val EXTRA_SOCKS_PORT = "socks_port"

        private const val KEY_SUCCESS = "success"
        private const val KEY_CODE = "code"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STATE = "state"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_SOCKS_HOST = "socks_host"
        private const val KEY_SOCKS_PORT = "socks_port"
        private const val KEY_VPN_PERMISSION_GRANTED = "vpn_permission_granted"
        private const val KEY_NATIVE_RUNNING = "native_running"
        private const val KEY_PENDING_COMMAND = "pending_command"
        private const val KEY_ERROR = "error"
        private const val KEY_TX_PACKETS = "tx_packets"
        private const val KEY_TX_BYTES = "tx_bytes"
        private const val KEY_RX_PACKETS = "rx_packets"
        private const val KEY_RX_BYTES = "rx_bytes"
    }
}
