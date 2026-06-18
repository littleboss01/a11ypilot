package com.mrgreenapps.a11ypilot.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.MainActivity
import com.mrgreenapps.a11ypilot.R
import com.mrgreenapps.a11ypilot.agent.AgentSettings
import com.mrgreenapps.a11ypilot.agent.NetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [McpServer] lifetime so the Ktor instance survives screen-off.
 * Posts a sticky notification showing the LAN URL.
 */
class McpService : android.app.Service() {

    private var server: McpServer? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ctx = applicationContext
        val port = intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 } ?: AgentSettings.DEFAULT_MCP_PORT
        val token = intent?.getStringExtra(EXTRA_TOKEN)
            ?: AgentSettings.ensureMcpToken(ctx)

        startInForeground(buildNotification(port))

        if (server == null) {
            EventLog.append("mcp> service starting (port=$port)")
            server = McpServer(ctx, port, token).also {
                try {
                    it.start()
                } catch (t: Throwable) {
                    EventLog.append("mcp> start threw: ${t.javaClass.simpleName}: ${t.message}")
                    McpServer.LAST_STATUS = "Start threw: ${t.message}"
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.stop() } catch (_: Throwable) {}
        server = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(port: Int): Notification {
        val ip = NetUtil.activeIpv4(applicationContext) ?: "0.0.0.0"
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mcp_notification_title))
            .setContentText("http://$ip:$port/mcp")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIF_ID = 4242
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_TOKEN = "extra_token"

        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Reads the configured port + bearer asynchronously, then starts the foreground service
         * with both passed via intent extras. This avoids any blocking on the caller's thread.
         */
        fun start(ctx: Context) {
            ioScope.launch {
                val snap = try { AgentSettings.snapshot(ctx) } catch (t: Throwable) {
                    EventLog.append("mcp> settings snapshot failed: ${t.message}"); null
                }
                val port = snap?.mcpPort ?: AgentSettings.DEFAULT_MCP_PORT
                val token = snap?.mcpToken?.takeIf { it.isNotEmpty() }
                    ?: AgentSettings.ensureMcpToken(ctx)
                val intent = Intent(ctx, McpService::class.java)
                    .putExtra(EXTRA_PORT, port)
                    .putExtra(EXTRA_TOKEN, token)
                try {
                    ContextCompat.startForegroundService(ctx, intent)
                } catch (t: Throwable) {
                    EventLog.append("mcp> startForegroundService threw: ${t.message}")
                    McpServer.LAST_STATUS = "Cannot start FGS: ${t.message}"
                }
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, McpService::class.java))
            McpServer.LAST_STATUS = "Stopped"
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.mcp_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }
}
