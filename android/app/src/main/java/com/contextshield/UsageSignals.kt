package com.contextshield

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

data class RemoteAccessSignal(val foundInActiveWindow: Boolean, val coarseAt: Long? = null)

/** Queries an explicitly enabled, narrow 15-minute window only when the user initiates a demo transfer. */
object UsageSignals {
    private const val WINDOW_MS = 15 * 60 * 1000L
    private val remoteSupportPackages = setOf(
        "com.anydesk.anydeskandroid",
        "com.carriez.flutter_hbb",
        "com.teamviewer.teamviewer.market.mobile",
        "com.teamviewer.quicksupport.market"
    )

    fun hasAccess(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun recentRemoteAccess(context: Context, now: Long = System.currentTimeMillis()): RemoteAccessSignal {
        if (!hasAccess(context)) return RemoteAccessSignal(false)
        return runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val usageEvents = manager.queryEvents((now - WINDOW_MS).coerceAtLeast(0), now)
            val event = UsageEvents.Event()
            var foundAt: Long? = null
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val isForeground = if (Build.VERSION.SDK_INT >= 29) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (isForeground && event.packageName in remoteSupportPackages) {
                    foundAt = event.timeStamp
                }
            }
            RemoteAccessSignal(foundAt != null, foundAt?.let { it / (5 * 60 * 1000L) * (5 * 60 * 1000L) })
        }.getOrDefault(RemoteAccessSignal(false))
    }
}
