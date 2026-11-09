package com.rwai.agent.phone

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.rwai.agent.logic.AppActivity
import com.rwai.agent.logic.AppFacts
import java.util.Calendar

/** Сбор сведений о приложениях для [com.rwai.agent.logic.PhoneAudit]. Видны запускаемые приложения и включённые службы. */
object PhoneScan {
    fun hasUsageAccess(c: Context): Boolean = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun usageSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun usageToday(c: Context): Map<String, Int> {
        if (!hasUsageAccess(c)) return emptyMap()
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
                .groupBy { it.packageName }.mapValues { (_, v) -> (v.sumOf { it.totalTimeInForeground } / 60_000L).toInt() }
        } catch (_: Exception) { emptyMap() }
    }

    /** Что делали приложения за последние [windowMin] минут: время на экране и со службой переднего плана (журнал событий Android). */
    fun recentActivity(c: Context, windowMin: Int = 60): List<AppActivity> {
        if (!hasUsageAccess(c)) return emptyList()
        val now = System.currentTimeMillis()
        val from = now - windowMin * 60_000L
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val fg = HashMap<String, Long>()          // пакет → мс на экране
        val svc = HashMap<String, Long>()         // пакет → мс со службой
        val lastFg = HashMap<String, Long>()      // пакет → когда последний раз был на экране
        val openFg = HashMap<String, Long>()      // компонент → начало
        val openSvc = HashMap<String, Long>()
        try {
            val ev = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                val pkg = e.packageName ?: continue
                val key = pkg + "/" + (e.className ?: "")
                val t = e.timeStamp
                when (e.eventType) {
                    1 -> openFg[key] = t // ACTIVITY_RESUMED
                    2 -> { // ACTIVITY_PAUSED
                        val s = openFg.remove(key) ?: from
                        fg[pkg] = (fg[pkg] ?: 0L) + (t - s).coerceAtLeast(0)
                        lastFg[pkg] = t
                    }
                    19 -> openSvc[key] = t // FOREGROUND_SERVICE_START
                    20 -> { // FOREGROUND_SERVICE_STOP
                        val s = openSvc.remove(key) ?: from
                        svc[pkg] = (svc[pkg] ?: 0L) + (t - s).coerceAtLeast(0)
                    }
                }
            }
        } catch (_: Exception) { return emptyList() }
        val nowFg = HashSet<String>()
        val nowSvc = HashSet<String>()
        for ((k, s) in openFg) { val p = k.substringBefore('/'); fg[p] = (fg[p] ?: 0L) + (now - s); lastFg[p] = now; nowFg += p }
        for ((k, s) in openSvc) { val p = k.substringBefore('/'); svc[p] = (svc[p] ?: 0L) + (now - s); nowSvc += p }
        val pm = c.packageManager
        val out = ArrayList<AppActivity>()
        for (p in fg.keys + svc.keys) {
            var label: String? = null
            var kind: String? = null
            try {
                val ai = pm.getApplicationInfo(p, 0)
                label = pm.getApplicationLabel(ai).toString()
                if (Build.VERSION.SDK_INT >= 26) kind = when (ai.category) {
                    ApplicationInfo.CATEGORY_GAME -> "игра"; ApplicationInfo.CATEGORY_VIDEO -> "видео"; ApplicationInfo.CATEGORY_MAPS -> "карты"
                    ApplicationInfo.CATEGORY_AUDIO -> "музыка"; ApplicationInfo.CATEGORY_SOCIAL -> "соцсеть"; else -> null
                }
            } catch (_: Exception) { }
            // Системные службы без названия (оболочка, SystemUI) только шумят: пропускаем.
            if (label == null && (p == "android" || p.startsWith("com.android.") || p.startsWith("com.google.android.gms"))) continue
            out += AppActivity(
                pkg = p, label = label ?: p.substringAfterLast('.'),
                foregroundMin = ((fg[p] ?: 0L) / 60_000L).toInt(), serviceMin = ((svc[p] ?: 0L) / 60_000L).toInt(),
                serviceNow = p in nowSvc,
                lastForegroundMinAgo = lastFg[p]?.let { ((now - it) / 60_000L).toInt() },
                kind = kind,
            )
        }
        return out
    }

    fun scan(c: Context): List<AppFacts> {
        val pm = c.packageManager
        val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
        val a11y = (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
        val listeners = try { NotificationManagerCompat.getEnabledListenerPackages(c) } catch (_: Exception) { emptySet<String>() }
        val admins = try {
            ((c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).activeAdmins ?: emptyList()).map { it.packageName }.toSet()
        } catch (_: Exception) { emptySet<String>() }
        val usage = usageToday(c)
        val pkgs = launcher + a11y + listeners + admins
        val out = ArrayList<AppFacts>()
        for (p in pkgs) {
            try {
                val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(p, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                else @Suppress("DEPRECATION") pm.getPackageInfo(p, PackageManager.GET_PERMISSIONS)
                val ai = info.applicationInfo ?: continue
                val requested = info.requestedPermissions ?: emptyArray()
                val flags = info.requestedPermissionsFlags ?: IntArray(0)
                val granted = requested.indices.filter { i -> i < flags.size && (flags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
                    .map { requested[it] }.toSet() + requested.filter { it == "android.permission.SYSTEM_ALERT_WINDOW" || it == "android.permission.REQUEST_INSTALL_PACKAGES" }
                val installer = try {
                    if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(p).installingPackageName
                    else @Suppress("DEPRECATION") pm.getInstallerPackageName(p)
                } catch (_: Exception) { null }
                out += AppFacts(
                    pkg = p, label = pm.getApplicationLabel(ai).toString(), installer = installer,
                    system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    permissions = granted, accessibilityOn = p in a11y, notificationListenerOn = p in listeners, deviceAdmin = p in admins,
                    foregroundMinutes = usage[p],
                )
            } catch (_: Exception) {
                // приложение скрыто или удалено — пропускаем
            }
        }
        return out
    }
}
