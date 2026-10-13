package dev.legendarymsr.lfsdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the build alive with the screen off: a foreground service with a progress notification. */
class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Boolean
        get() = active
        set(v) { active = v }
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = Builder.get(this)
        if (intent?.action == ACTION_STOP) {
            scope.launch { builder.stop() }
            return START_NOT_STICKY
        }
        startForeground(NOTE_ID, note(builder.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (running) return START_STICKY
        // Restarted by the system (null intent) or reattaching: only monitor if a build is alive.
        val launch = intent?.action == ACTION_START
        if (!launch && !builder.isRunning()) { stopSelf(); return START_NOT_STICKY }
        running = true
        if (wake?.isHeld != true) {
            // No timeout: held exactly as long as the build runs, released in finish().
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lfsdroid:build").apply { setReferenceCounted(false); acquire() }
        }
        val nm = getSystemService(NotificationManager::class.java)
        scope.launch {
            builder.state.map { Triple(it.phase, it.headline, (it.fraction * 1000).toInt()) }.distinctUntilChanged().collectLatest {
                runCatching { nm.notify(NOTE_ID, note(builder.state.value)) }
            }
        }
        scope.launch {
            try {
                if (launch && !builder.isRunning()) { if (!builder.start()) return@launch }
                // The build is a detached root process; this only watches its files.
                while (isActive) {
                    val rs = builder.poll()
                    if (rs != RunState.Running) break
                    delay(1000)
                }
                builder.poll()
            } catch (e: Exception) {
                CrashLog.write(this@BuildService, "build monitor", e)
            } finally {
                finish(nm)
            }
        }
        return START_STICKY
    }

    private fun finish(nm: NotificationManager) {
        running = false
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_DETACH)
        runCatching { nm.notify(NOTE_ID, note(Builder.get(this).state.value, ongoing = false)) }
        stopSelf()
    }

    /** Android 15 times out some FGS types; specialUse has none, but if it ever fires, the build itself carries on. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun note(p: BuildProgress, ongoing: Boolean = true): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "LFS build", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BuildService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(p.headline)
            .setContentText(p.log.lastOrNull() ?: p.message)
            .setContentIntent(open)
            .setOngoing(ongoing && p.phase != BuildProgress.Phase.Done && p.phase != BuildProgress.Phase.Failed)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) {
                    setProgress(1000, (p.fraction * 1000).toInt(), p.total == 0)
                    addAction(Notification.Action.Builder(null, "Stop", stop).build())
                }
            }
            .build()
    }

    companion object {
        private const val CHANNEL = "build"
        private const val NOTE_ID = 1
        const val ACTION_STOP = "dev.legendarymsr.lfsdroid.STOP"

        @Volatile var active = false
        const val ACTION_START = "dev.legendarymsr.lfsdroid.START"
        fun start(c: Context) = c.startForegroundService(Intent(c, BuildService::class.java).setAction(ACTION_START))

        /** Re-attach the notification and monitor to a build that's still running (e.g. after the app was killed). */
        fun attach(c: Context) = c.startForegroundService(Intent(c, BuildService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, BuildService::class.java).setAction(ACTION_STOP))
    }
}
