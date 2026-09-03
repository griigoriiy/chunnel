package com.mobileapp.charlestunnel

enum class TunnelState(val wireName: String) {
    IDLE("idle"),
    STARTING("starting"),
    RUNNING("running"),
    STOPPING("stopping"),
    ERROR("error");

    companion object {
        fun fromWireName(value: String?): TunnelState = entries.firstOrNull { it.wireName == value } ?: IDLE
    }
}
