package com.acegallery.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var queue: DownloadQueue

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "下载",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(done = 0, total = 0))
        job?.cancel()
        job = scope.launch {
            var lastNotifiedAt = 0L
            queue.items.collectLatest { items ->
                val active = items.count {
                    it.state is DownloadManager.State.Queued ||
                        it.state is DownloadManager.State.Downloading
                }
                val done = items.count { it.state is DownloadManager.State.Done }
                if (items.isEmpty() || active == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastNotifiedAt >= 500) {
                        lastNotifiedAt = now
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(done = done, total = items.size))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ace 相册")
            .setContentText(if (total == 0) "准备下载" else "正在下载 $done/$total")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java),
            )
        }
    }
}
