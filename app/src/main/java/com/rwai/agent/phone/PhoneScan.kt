package com.rwai.agent.phone

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
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
