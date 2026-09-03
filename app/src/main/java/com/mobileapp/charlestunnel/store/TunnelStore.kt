package com.mobileapp.charlestunnel.store

import android.content.Context
import android.os.SystemClock
import com.mobileapp.charlestunnel.TunnelConfig
import com.mobileapp.charlestunnel.TunnelState

class TunnelStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun config(): TunnelConfig {
        val host = preferences.getString(KEY_HOST, TunnelConfig.DEFAULT_HOST) ?: TunnelConfig.DEFAULT_HOST
        val port = preferences.getInt(KEY_PORT, TunnelConfig.DEFAULT_PORT)
        return TunnelConfig(host, port)
    }

    fun saveConfig(config: TunnelConfig) {
        preferences.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .apply()
    }

    fun state(): TunnelState = TunnelState.fromWireName(preferences.getString(KEY_STATE, null))

    fun setState(state: TunnelState, error: String? = null) {
        preferences.edit()
            .putString(KEY_STATE, state.wireName)
            .putString(KEY_ERROR, error)
            .apply()
    }

    fun lastError(): String? = preferences.getString(KEY_ERROR, null)

    fun stage(command: PendingCommand) = synchronized(commandLock) {
        preferences.edit()
            .putString(KEY_PENDING_COMMAND, command.wireName)
            .putLong(KEY_PENDING_AT, SystemClock.elapsedRealtime())
            .commit()
    }

    fun pendingCommandName(): String? = synchronized(commandLock) {
        readPending(remove = false)?.wireName
    }

    fun consumePending(): PendingCommand? = synchronized(commandLock) {
        readPending(remove = true)
    }

    private fun readPending(remove: Boolean): PendingCommand? {
        val name = preferences.getString(KEY_PENDING_COMMAND, null) ?: return null
        val createdAt = preferences.getLong(KEY_PENDING_AT, 0L)
        val age = SystemClock.elapsedRealtime() - createdAt
        val command = PendingCommand.fromWireName(name).takeIf { age in 0..PENDING_TTL_MS }
        if (remove || command == null) {
            preferences.edit()
                .remove(KEY_PENDING_COMMAND)
                .remove(KEY_PENDING_AT)
                .commit()
        }
        return command
    }

    companion object {
        private const val PREFERENCES_NAME = "tunnel"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_STATE = "state"
        private const val KEY_ERROR = "error"
        private const val KEY_PENDING_COMMAND = "pending_command"
        private const val KEY_PENDING_AT = "pending_at"
        private const val PENDING_TTL_MS = 30_000L
        private val commandLock = Any()
    }
}

enum class PendingCommand(val wireName: String) {
    START("start"),
    STOP("stop");

    companion object {
        fun fromWireName(value: String?): PendingCommand? = entries.firstOrNull { it.wireName == value }
    }
}
