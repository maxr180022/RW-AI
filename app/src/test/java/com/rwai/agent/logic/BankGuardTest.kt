package com.rwai.agent.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankGuardTest {
    @Test fun knownBankPackagesAreBlocked() {
        listOf("ru.sberbankmobile", "com.idamob.tinkoff.android", "com.paypal.android.p2pmobile", "uy.com.itau.mobile", "com.mercadopago.wallet")
            .forEach { assertTrue(it, BankGuard.isBankingPackage(it)) }
    }

    @Test fun unknownBankByPackageSegmentIsBlocked() {
        assertTrue(BankGuard.isBankingPackage("com.example.mybank.android"))
        assertTrue(BankGuard.isBankingPackage("uy.com.bancoxyz.app"))
    }

    @Test fun ordinaryAppsAreNotBlocked() {
        listOf("com.whatsapp", "org.telegram.messenger", "com.google.android.gm", "com.android.chrome", "com.instagram.android", "com.android.settings")
            .forEach { assertFalse(it, BankGuard.isBankingPackage(it)) }
        assertFalse(BankGuard.isBankingPackage(null))
        assertFalse(BankGuard.isBankingPackage(""))
    }

    @Test fun labelsAreMatchedInRussianSpanishEnglish() {
        listOf("Сбербанк Онлайн", "Т-Банк", "ВТБ Онлайн", "Banco República", "Santander", "PayPal", "Mercado Pago", "Мой кошелёк", "Binance", "Itaú")
            .forEach { assertTrue(it, BankGuard.isBankingLabel(it)) }
    }

    @Test fun labelsOfNormalAppsAreNotBlocked() {
        listOf("WhatsApp", "Gmail", "Telegram", "Instagram", "Chrome", "Календарь", "Настройки", "Карты", "Samsung Internet", "Фото")
            .forEach { assertFalse(it, BankGuard.isBankingLabel(it)) }
    }

    @Test fun wordBoundariesPreventFalsePositives() {
        assertFalse(BankGuard.isBankingLabel("Rewiser"))
        assertFalse(BankGuard.isBankingLabel("Otwise"))
    }

    @Test fun bankUrlsAreBlocked() {
        listOf(
            "https://www.sberbank.ru/ru/person", "https://online.tinkoff.ru/login", "https://paypal.com/signin",
            "http://www.brou.com.uy", "https://mybank.example.com/login", "https://ebanking.example.org",
        ).forEach { assertTrue(it, BankGuard.isBankingUrl(it)) }
    }

    @Test fun ordinaryUrlsAreNotBlocked() {
        listOf("https://www.google.com/search?q=bank", "https://mail.google.com", "https://t.me/user", "https://rusweo.com", "mailto:a@b.c", "tel:123")
            .forEach { assertFalse(it, BankGuard.isBankingUrl(it)) }
        assertFalse(BankGuard.isBankingUrl(null))
    }

    @Test fun refusalMessageIsNotEmpty() {
        assertTrue(BankGuard.REFUSAL.contains("Банковские"))
    }
}
