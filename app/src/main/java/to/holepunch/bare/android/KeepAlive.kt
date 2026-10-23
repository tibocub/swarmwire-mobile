package to.holepunch.bare.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

// Does nothing but hold a notification, which is what stops Android from freezing the app (and so the
// worklet) while a transfer runs with the screen off or another app in front.
// shortcut: Android 15 ends a dataSync service after about 6 hours; restart it from onTimeout if that matters.
class KeepAlive : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel("transfer", "Transfers", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val note = Notification.Builder(this, "transfer")
      .setSmallIcon(android.R.drawable.stat_sys_upload_done)
      .setContentTitle("swarmwire")
      .setContentText("Transferring. Tap to open.")
      .setContentIntent(open)
      .setOngoing(true)
      .build()
    startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    return START_NOT_STICKY // a restarted service would have no worklet to keep alive
  }
}
