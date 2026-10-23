package to.holepunch.bare.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

// Does nothing but hold a notification, which is what stops Android from freezing the app (and so the
// worklet) while transfers run with the screen off or another app in front. MainActivity keeps its
// text current with `notification`.
// shortcut: Android 15 ends a dataSync service after about 6 hours; restart it from onTimeout if that matters.
class KeepAlive : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel("transfer", "Transfers", NotificationManager.IMPORTANCE_LOW))
    startForeground(ID, notification(this, intent?.getStringExtra("text") ?: "swarmwire", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    return START_NOT_STICKY // a restarted service would have no worklet to keep alive
  }

  companion object {
    const val ID = 1

    /** `percent` below 0 shows no progress bar. */
    fun notification(context: Context, text: String, percent: Int): Notification {
      val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
      return Notification.Builder(context, "transfer")
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle("swarmwire")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .apply { if (percent >= 0) setProgress(100, percent, false) }
        .build()
    }
  }
}
