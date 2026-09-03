package com.mobileapp.charlestunnel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.mobileapp.charlestunnel.service.TunnelVpnService
import com.mobileapp.charlestunnel.nativebridge.NativeTunnel
import com.mobileapp.charlestunnel.store.PendingCommand
import com.mobileapp.charlestunnel.store.TunnelStore

class MainActivity : Activity() {
    private lateinit var store: TunnelStore
    private lateinit var endpointInput: EditText
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var actionButton: Button
    private lateinit var adbHint: TextView
    private var startAfterNotificationPermission = false
    private var finishAfterSuccessfulAdbStart = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startAfterNotificationPermission =
            savedInstanceState?.getBoolean(STATE_START_AFTER_NOTIFICATION_PERMISSION) == true
        finishAfterSuccessfulAdbStart =
            savedInstanceState?.getBoolean(STATE_FINISH_AFTER_ADB_START) == true
        setContentView(R.layout.activity_main)
        configureEdgeToEdge()
        applySystemBarInsets()
        store = TunnelStore(this)
        reconcileTunnelState()
        bindViews()
        showConfig(store.config())
        actionButton.setOnClickListener { onActionClicked() }
        val commandConsumed = consumePendingCommand()
        if (!commandConsumed && savedInstanceState == null) {
            requestNotificationPermission(startAfterGrant = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePendingCommand()
    }

    private fun reconcileTunnelState() {
        if (NativeTunnel.isRunning()) return

        when (store.state()) {
            TunnelState.STARTING, TunnelState.RUNNING -> store.setState(
                TunnelState.ERROR,
                getString(R.string.tunnel_native_failed, NativeTunnel.lastResult()),
            )

            TunnelState.STOPPING -> store.setState(TunnelState.IDLE)
            TunnelState.IDLE, TunnelState.ERROR -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val lightSystemBarIcons =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val appearanceMask =
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                if (lightSystemBarIcons) appearanceMask else 0,
                appearanceMask,
            )
        } else {
            var flags =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (lightSystemBarIcons) {
                flags = flags or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_START_AFTER_NOTIFICATION_PERMISSION,
            startAfterNotificationPermission,
        )
        outState.putBoolean(
            STATE_FINISH_AFTER_ADB_START,
            finishAfterSuccessfulAdbStart,
        )
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return

        val shouldStart = startAfterNotificationPermission
        startAfterNotificationPermission = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (shouldStart) requestVpnAndStart()
        } else if (shouldStart) {
            store.setState(TunnelState.ERROR, getString(R.string.notification_permission_denied))
            render()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_PERMISSION_REQUEST) return
        if (resultCode == RESULT_OK) {
            startTunnelService()
        } else {
            store.setState(TunnelState.ERROR, getString(R.string.vpn_permission_denied))
        }
    }

    private fun bindViews() {
        endpointInput = findViewById(R.id.endpoint_input)
        statusText = findViewById(R.id.status_text)
        errorText = findViewById(R.id.error_text)
        actionButton = findViewById(R.id.action_button)
        adbHint = findViewById(R.id.adb_hint)
    }

    private fun consumePendingCommand(): Boolean =
        when (store.consumePending()) {
            PendingCommand.START -> {
                finishAfterSuccessfulAdbStart = true
                showConfig(store.config())
                requestNotificationPermissionAndStart()
                true
            }

            PendingCommand.STOP -> {
                stopTunnelService()
                true
            }

            null -> false
        }

    private fun onActionClicked() {
        when (store.state()) {
            TunnelState.STARTING, TunnelState.RUNNING, TunnelState.STOPPING -> {
                finishAfterSuccessfulAdbStart = false
                stopTunnelService()
            }
            TunnelState.IDLE, TunnelState.ERROR -> validateAndStart()
        }
    }

    private fun validateAndStart() {
        when (val validation = TunnelConfig.parse(endpointInput.text.toString())) {
            is ConfigValidation.Valid -> {
                store.saveConfig(validation.config)
                requestNotificationPermissionAndStart()
            }
            is ConfigValidation.Invalid -> {
                val message = when (validation.error) {
                    ConfigError.INVALID_ENDPOINT -> R.string.invalid_endpoint
                    ConfigError.INVALID_HOST -> R.string.invalid_host
                    ConfigError.INVALID_PORT -> R.string.invalid_port
                }
                store.setState(TunnelState.ERROR, getString(message))
                render()
            }
        }
    }

    private fun requestNotificationPermissionAndStart() {
        if (requestNotificationPermission(startAfterGrant = true)) {
            requestVpnAndStart()
        }
    }

    private fun requestNotificationPermission(startAfterGrant: Boolean): Boolean {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        startAfterNotificationPermission = startAfterGrant
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST,
        )
        return false
    }

    private fun requestVpnAndStart() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            startTunnelService()
        } else {
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST)
        }
    }

    private fun startTunnelService() {
        store.setState(TunnelState.STARTING)
        val serviceIntent = Intent(this, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_START)
        startForegroundService(serviceIntent)
        render()
    }

    private fun stopTunnelService() {
        store.setState(TunnelState.STOPPING)
        val serviceIntent = Intent(this, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_STOP)
        startService(serviceIntent)
        render()
    }

    private fun showConfig(config: TunnelConfig) {
        endpointInput.setText(config.endpoint)
        adbHint.text = getString(R.string.adb_hint, config.port)
    }

    private fun render() {
        val state = store.state()
        if (state == TunnelState.RUNNING && finishAfterSuccessfulAdbStart) {
            finishAfterSuccessfulAdbStart = false
            finish()
            return
        }
        if (state == TunnelState.ERROR) {
            finishAfterSuccessfulAdbStart = false
        }
        val stateLabel = when (state) {
            TunnelState.IDLE -> R.string.status_idle
            TunnelState.STARTING -> R.string.status_starting
            TunnelState.RUNNING -> R.string.status_running
            TunnelState.STOPPING -> R.string.status_stopping
            TunnelState.ERROR -> R.string.status_error
        }
        statusText.text = getString(R.string.status_format, getString(stateLabel))
        actionButton.setText(
            if (state == TunnelState.RUNNING || state == TunnelState.STARTING || state == TunnelState.STOPPING) {
                R.string.action_stop
            } else {
                R.string.action_start
            },
        )
        val editable = state == TunnelState.IDLE || state == TunnelState.ERROR
        endpointInput.isEnabled = editable
        val error = store.lastError()
        errorText.text = error.orEmpty()
        errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 1
        private const val NOTIFICATION_PERMISSION_REQUEST = 2
        private const val STATE_START_AFTER_NOTIFICATION_PERMISSION =
            "start_after_notification_permission"
        private const val STATE_FINISH_AFTER_ADB_START = "finish_after_adb_start"
        private const val STATUS_REFRESH_MS = 1_000L
    }
}
