package com.rwai.agent.logic

/** Сведения об одном приложении (собираются на телефоне, никуда не отправляются). */
data class AppFacts(
    val pkg: String,
    val label: String,
    val installer: String?,
    val system: Boolean,
    val permissions: Set<String>,
    val accessibilityOn: Boolean = false,
    val notificationListenerOn: Boolean = false,
    val deviceAdmin: Boolean = false,
    val foregroundMinutes: Int? = null,
)

data class Finding(val level: Int, val app: String, val text: String) // level: 3 высокий, 2 средний, 1 заметка

/**
 * Эвристическая проверка установленных приложений. Это НЕ антивирус: он не видит код приложений и не знает вирусных баз.
 * Он показывает подозрительные признаки (установка вне магазина + опасные доступы), чтобы владелец проверил их сам.
 */
object PhoneAudit {
    val trustedInstallers = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.mipicks", "com.miui.packageinstaller.store",
        "com.amazon.venezia", "ru.vk.store", "com.heytap.market", "com.oppo.market", "com.vivo.appstore", "com.google.android.feedback",
        "com.sec.android.easyMover", "com.android.shell.never",
    )
    private val dangerous = setOf(
        "android.permission.RECORD_AUDIO", "android.permission.CAMERA", "android.permission.READ_SMS", "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS", "android.permission.READ_CONTACTS", "android.permission.READ_CALL_LOG", "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE",
    )
    private val smsLike = setOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS")
    private const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    private const val INSTALL = "android.permission.REQUEST_INSTALL_PACKAGES"

    fun isSideloaded(a: AppFacts) = !a.system && (a.installer == null || a.installer !in trustedInstallers)

    fun analyze(apps: List<AppFacts>, ownPackage: String): List<Finding> {
        val out = ArrayList<Finding>()
        for (a in apps) {
            if (a.pkg == ownPackage) continue
            val side = isSideloaded(a)
            val risky = mutableListOf<String>()
            if (a.accessibilityOn) risky += "читает экран и может нажимать вместо вас (служба специальных возможностей)"
            if (a.notificationListenerOn) risky += "читает все уведомления"
            if (a.deviceAdmin) risky += "является администратором устройства"
            if (OVERLAY in a.permissions) risky += "может рисовать поверх других приложений"
            if (a.permissions.any { it in smsLike }) risky += "работает с SMS"
            if (INSTALL in a.permissions && !a.system) risky += "может устанавливать другие приложения"
            val dangerousCount = a.permissions.count { it in dangerous }
            when {
                side && risky.isNotEmpty() ->
                    out += Finding(3, a.label, "Установлено не из магазина и " + risky.joinToString("; ") + ". Если вы это не ставили осознанно — удалите.")
                !a.system && (a.accessibilityOn || a.deviceAdmin) ->
                    out += Finding(2, a.label, "Имеет мощный доступ: " + risky.joinToString("; ") + ". Убедитесь, что это нужное вам приложение.")
                !a.system && a.notificationListenerOn ->
                    out += Finding(1, a.label, "Читает все уведомления. Если приложение не нужно для этого — отключите доступ.")
                side && dangerousCount >= 5 ->
                    out += Finding(2, a.label, "Установлено не из магазина и запросило $dangerousCount опасных разрешений.")
                side -> out += Finding(1, a.label, "Установлено не из магазина (источник: ${a.installer ?: "неизвестен"}).")
            }
        }
        return out.sortedWith(compareByDescending<Finding> { it.level }.thenBy { it.app })
    }

    fun summary(findings: List<Finding>): String {
        val high = findings.count { it.level >= 3 }
        val mid = findings.count { it.level == 2 }
        return when {
            high > 0 -> "Найдено подозрительных: $high (серьёзных), $mid (средних). Проверьте список ниже."
            mid > 0 -> "Серьёзных признаков нет, но есть $mid приложений с мощным доступом — проверьте список."
            findings.isNotEmpty() -> "Явных угроз по признакам не видно. Заметок: ${findings.size}."
            else -> "Подозрительных признаков не найдено."
        }
    }

    /** Приложения, которые сегодня дольше всего были на экране: ближайший честный признак «что греет и сажает батарею». */
    fun topUsage(apps: List<AppFacts>, n: Int = 5): List<AppFacts> =
        apps.filter { (it.foregroundMinutes ?: 0) > 0 }.sortedByDescending { it.foregroundMinutes }.take(n)
}
