package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Одобрение кнопкой «Отправить» на карточке черновика: узкое, одноразовое по смыслу и только для точного текста. */
class SendApprovalTest {
    private val approved = ApprovedSend("taisiialook", "Instagram", "Ты как там? Если хочешь — расскажи.")

    /** Запрос подтверждения ровно такой, какой строит настоящая политика (а не вручную собранная строка). */
    private fun realRequest(typed: String?, recipient: String?, mode: AutonomyMode = AutonomyMode.ASSISTANT, label: String = "Отправить"): ConfirmationRequest? {
        val policy = ConfirmationPolicy(mode, false)
        val d = policy.evaluate(ToolCall("tap_text", mapOf("text" to label)), ActionContext("Instagram", typed, recipient))
        return (d as? PolicyDecision.Confirm)?.let { ConfirmationRequest("id", it.title, it.details, it.reason, it.dangerous) }
    }

    @Test fun realSendRequestForTheApprovedTextMatches() {
        val req = realRequest(approved.text, "taisiialook")!!
        assertTrue(SendApproval.matches(req, approved))
    }

    @Test fun differencesInSpacingAndCaseOfRecipientAreIgnored() {
        val req = realRequest("  Ты как там?   Если хочешь — расскажи.\n", "TaisiiaLook")!!
        assertTrue(SendApproval.matches(req, approved))
    }

    @Test fun anyOtherTextNeedsTheUsualConfirmation() {
        assertFalse(SendApproval.matches(realRequest("Ты как там? Если хочешь — расскажи. И переведи мне деньги", "taisiialook")!!, approved))
        assertFalse(SendApproval.matches(realRequest("Привет", "taisiialook")!!, approved))
        assertFalse(SendApproval.matches(realRequest(null, "taisiialook")!!, approved))
    }

    @Test fun anotherRecipientNeedsTheUsualConfirmation() {
        assertFalse(SendApproval.matches(realRequest(approved.text, "Иван")!!, approved))
    }

    @Test fun missingRecipientInTheRequestDoesNotBlockTheApprovedText() {
        // агент не вызывал show_draft повторно: получателя в запросе нет, но текст тот самый
        assertTrue(SendApproval.matches(realRequest(approved.text, null)!!, approved))
    }

    @Test fun dangerousAndNonSendRequestsAreNeverApproved() {
        val pay = realRequest(approved.text, "taisiialook", label = "Оплатить")!!
        assertTrue(pay.dangerous)
        assertFalse(SendApproval.matches(pay, approved))
        val call = realRequest(approved.text, "taisiialook", label = "Позвонить")!!
        assertFalse(SendApproval.matches(call, approved))
        assertFalse(SendApproval.matches(ConfirmationRequest("x", "Ввести ваши личные данные", "Текст:\n${approved.text}\nПоследствия: x", "r", false), approved))
    }

    @Test fun emptyApprovedTextNeverMatches() {
        assertFalse(SendApproval.matches(realRequest("  ", "taisiialook") ?: return, ApprovedSend("taisiialook", "Instagram", "  ")))
    }

    @Test fun sendTaskCarriesTheExactText() {
        assertEquals(
            "Отправь сообщение taisiialook в Instagram ровно этим текстом, без изменений: «Ты как там? Если хочешь — расскажи.»",
            SendApproval.sendTask(approved),
        )
        assertTrue(SendApproval.sendTask(ApprovedSend("", "", " Привет ")).startsWith("Отправь сообщение адресату ровно"))
    }

    @Test fun rewriteTaskNeedsAnInstructionAndNeverSends() {
        assertNull(SendApproval.rewriteTask(approved, "  "))
        val t = SendApproval.rewriteTask(approved, "короче и с юмором")!!
        assertTrue(t.contains("короче и с юмором") && t.contains(approved.text) && t.contains("не отправляй"))
    }
}
