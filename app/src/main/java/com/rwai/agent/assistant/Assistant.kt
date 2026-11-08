package com.rwai.agent.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Делает Руся AI доступным в списке «Цифровой помощник по умолчанию». Когда владелец выбирает его там,
 * жест вызова ассистента (долгое нажатие кнопки «Домой» или боковой кнопки) показывает панель Руся AI поверх экрана с голосовым вводом.
 */
class RwVoiceInteractionService : VoiceInteractionService()

class RwSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = RwSession(this)
}

class RwSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistantOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            try { context.startActivity(intent) } catch (_: Exception) { }
        }
        hide()
    }
}

/**
 * Обязательная для регистрации ассистента служба распознавания. Она ничего не распознаёт:
 * голосовой ввод Руся AI использует системное распознавание Google/Samsung.
 */
class RwRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) { }
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
