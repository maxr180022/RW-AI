package com.rwai.agent.logic

/**
 * Жёсткий запрет: агент не открывает банковские и платёжные приложения и сайты,
 * не читает их экран и не сохраняет их уведомления. Правило не отключается настройками
 * и не зависит от режима автономности — модель не может его обойти.
 *
 * Это эвристика (список известных приложений + признаки в названии), поэтому
 * владелец должен понимать: редкое банковское приложение с нестандартным названием может не распознаться.
 */
object BankGuard {
    /** Точные имена пакетов известных банков, кошельков и бирж. */
    private val packages: Set<String> = setOf(
        // Уругвай
        "uy.com.brou.mobile", "com.brou.app", "uy.com.itau.mobile", "com.itau.uruguay", "com.santander.uy",
        "com.scotiabank.uy", "com.bbva.uy", "com.hsbc.hsbcuruguay", "uy.com.prex", "com.prexcard.app",
        "uy.com.midinero", "com.zinli.app", "com.mercadopago.wallet", "uy.com.redpagos", "uy.com.abitab",
        // Россия и СНГ
        "ru.sberbankmobile", "ru.sberbank.sberbankid", "com.idamob.tinkoff.android", "ru.tinkoff.mb", "ru.tbank.mb",
        "ru.vtb24.mobilebanking.android", "ru.alfabank.mobile.android", "ru.raiffeisennews", "ru.sovcombank.android",
        "ru.gazprombank.android.mobilebank.app", "ru.rosbank.android", "ru.psbank.android", "ru.yoo.money",
        "ru.mw", "com.openbank", "ru.mtsbank.android",
        // Международные
        "com.paypal.android.p2pmobile", "com.revolut.revolut", "com.transferwise.android", "com.binance.dev",
        "com.coinbase.android", "com.bybit.app", "com.wallet.crypto.trustapp", "io.metamask", "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay", "com.samsung.android.samsungpay.gear", "com.venmo", "com.squareup.cash",
        "com.payoneer.android", "com.skrill.wallet", "net.payoneer.pm", "com.westernunion.moneytransferr3app.eu",
    )

    /** Фрагменты имени пакета (сегменты), надёжно указывающие на банк. */
    private val packageSegmentHints = listOf("bank", "banco", "banca", "banque", "sberbank", "tinkoff", "tbank", "fintech", "neobank")

    private val labelRegex = Regex(
        "(?<![\\p{L}\\p{N}])(" +
            "банк[\\p{L}]*|bank[\\p{L}]*|banco[\\p{L}]*|banca[\\p{L}]*|" +
            "сбер[\\p{L}]*|sber[\\p{L}]*|тинькофф|т-банк|tinkoff|tbank|" +
            "втб|vtb|альфа[- ]?банк|райффайзен[\\p{L}]*|raiffeisen|газпромбанк|юmoney|юмани|qiwi|киви|" +
            "paypal|revolut|wise|binance|coinbase|bybit|metamask|trust wallet|crypto|" +
            "mercado ?pago|prex|brou|itaú|itau|santander|scotiabank|bbva|hsbc|midinero|zinli|abitab|redpagos|" +
            "google wallet|samsung wallet|samsung pay|кошел[её]к[\\p{L}]*|wallet|" +
            "western union|moneygram|payoneer|skrill|venmo|cash app" +
            ")(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /** Домены банков, платёжных систем и бирж (совпадение по окончанию имени хоста). */
    private val domains: List<String> = listOf(
        "sberbank.ru", "sber.ru", "tinkoff.ru", "tbank.ru", "vtb.ru", "alfabank.ru", "raiffeisen.ru", "gazprombank.ru",
        "sovcombank.ru", "yoomoney.ru", "qiwi.com", "paypal.com", "wise.com", "revolut.com", "binance.com", "coinbase.com",
        "bybit.com", "brou.com.uy", "itau.com.uy", "santander.com.uy", "scotiabank.com.uy", "bbva.com.uy", "hsbc.com.uy",
        "prexcard.com", "mercadopago.com", "mercadopago.com.uy", "midinero.com.uy", "zinli.com", "westernunion.com",
        "moneygram.com", "payoneer.com", "skrill.com", "venmo.com", "cash.app",
    )

    private val hostHints = listOf("bank", "banco", "banca", "banque")

    fun isBankingPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val p = pkg.lowercase()
        if (p in packages) return true
        return p.split('.').any { seg -> packageSegmentHints.any { seg.contains(it) } }
    }

    fun isBankingLabel(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        return labelRegex.containsMatchIn(label)
    }

    /** Проверка по паре «пакет + видимое название приложения». */
    fun isBanking(pkg: String?, label: String?): Boolean = isBankingPackage(pkg) || isBankingLabel(label)

    /** Проверка ссылки; не-http(s) и нераспознанные ссылки не считаются банковскими. */
    fun isBankingUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = hostOf(url) ?: return false
        if (domains.any { host == it || host.endsWith(".$it") }) return true
        return host.split('.').any { seg -> hostHints.any { seg.contains(it) } }
    }

    private fun hostOf(url: String): String? {
        val t = url.trim().lowercase()
        val noScheme = when {
            t.startsWith("https://") -> t.removePrefix("https://")
            t.startsWith("http://") -> t.removePrefix("http://")
            "://" in t -> return null
            else -> t
        }
        val host = noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':')
        return host.takeIf { it.isNotEmpty() }
    }

    const val REFUSAL = "Банковские и платёжные приложения и сайты закрыты для агента: RW AI не открывает их и не читает их экран. " +
        "Выполните это действие сами, без агента."
}
