package fm.heterodyne.app.preflight

import android.service.notification.NotificationListenerService

/** Lets the isolated test APK resolve its own live MediaSession through the Android API. */
class PreflightMediaListener : NotificationListenerService()
