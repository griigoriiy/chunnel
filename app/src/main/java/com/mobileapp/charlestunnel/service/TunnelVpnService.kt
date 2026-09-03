package com.mobileapp.charlestunnel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.mobileapp.charlestunnel.MainActivity
import com.mobileapp.charlestunnel.R
import com.mobileapp.charlestunnel.TunnelConfig
import com.mobileapp.charlestunnel.TunnelState
import com.mobileapp.charlestunnel.nativebridge.NativeTunnel
import com.mobileapp.charlestunnel.store.TunnelStore
import java.util.concurrent.Executors

class TunnelVpnService : VpnService() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: TunnelStore
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        store = TunnelStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> executor.execute { stopTunnel() }
            ACTION_START -> {
                val config = store.config()
                startForegroundCompat(notification(config))
                executor.execute { startTunnel(config) }
            }
            else -> stopSelf(startId)
        }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        executor.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunInterface()
        NativeTunnel.stop()
        if (store.state() != TunnelState.ERROR) {
            store.setState(TunnelState.IDLE)
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel(config: TunnelConfig) {
        if (NativeTunnel.isRunning()) {
            closeTunInterface()
            NativeTunnel.stop()
        }

        store.setState(TunnelState.STARTING)
        val descriptor = runCatching { establishInterface() }.getOrNull()
        if (descriptor == null) {
            fail(getString(R.string.tunnel_establish_failed))
            return
        }

        tunInterface = descriptor
        val result = NativeTunnel.start(cacheDir, config.host, config.port, descriptor.fd)
        if (result != 0) {
            closeTunInterface()
            fail(getString(R.string.tunnel_native_failed, result))
            return
        }
        store.setState(TunnelState.RUNNING)
    }

    private fun stopTunnel() {
        store.setState(TunnelState.STOPPING)
        closeTunInterface()
        NativeTunnel.stop()
        store.setState(TunnelState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTunInterface() {
        val descriptor = tunInterface ?: return
        tunInterface = null
        runCatching { descriptor.close() }
    }

    private fun fail(message: String) {
        store.setState(TunnelState.ERROR, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establishInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TUNNEL_MTU)
            .addAddress(TUNNEL_IPV4, 32)
            .addRoute("0.0.0.0", 0)
            .addAddress(TUNNEL_IPV6, 128)
            .addRoute("::", 0)
            .addDnsServer(MAPPED_DNS)
            .addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder.establish()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(config: TunnelConfig): Notification {
        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_ACTIVITY_REQUEST_CODE,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_TUNNEL_REQUEST_CODE,
            Intent(this, TunnelVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, config.endpoint))
            .setContentIntent(activityPendingIntent)
            .addAction(
                R.drawable.ic_tunnel,
                getString(R.string.action_stop),
                stopPendingIntent,
            )
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.mobileapp.charlestunnel.action.START"
        const val ACTION_STOP = "com.mobileapp.charlestunnel.action.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "tunnel"
        private const val NOTIFICATION_ID = 1
        private const val OPEN_ACTIVITY_REQUEST_CODE = 1
        private const val STOP_TUNNEL_REQUEST_CODE = 2
        private const val TUNNEL_MTU = 8500
        private const val TUNNEL_IPV4 = "198.18.0.1"
        private const val TUNNEL_IPV6 = "fc00::1"
        private const val MAPPED_DNS = "198.18.0.2"
    }
}
