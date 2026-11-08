package com.rwai.agent.security

import org.json.JSONObject

/**
 * Профиль владельца (имя, телефон, почта, адрес и т.п.). Хранится только на телефоне, одним шифртекстом AES-GCM
 * (ключ в Android Keystore, см. [SecretStore]). Значения НЕ отправляются модели — см. ProfileTemplates.
 * «Заметка о себе» — единственное поле, которое владелец осознанно отдаёт модели как справку.
 */
class ProfileStore(private val secrets: SecretStore) {
    private fun readAll(): JSONObject = try {
        secrets.get(NAME)?.let { JSONObject(it) } ?: JSONObject()
    } catch (_: Exception) { JSONObject() }

    /** Поля профиля (без заметки о себе). */
    fun values(): Map<String, String> {
        val j = readAll()
        val out = LinkedHashMap<String, String>()
        j.keys().forEach { k -> if (k != ABOUT) j.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
        return out
    }

    fun about(): String = readAll().optString(ABOUT, "")

    /** Сохраняет профиль. Возвращает ключи, которые не приняты (неверный ключ или номер карты). */
    fun save(values: Map<String, String>, about: String): List<String> {
        val rejected = mutableListOf<String>()
        val j = JSONObject()
        values.forEach { (k, raw) ->
            val v = raw.trim()
            if (v.isEmpty()) return@forEach
            if (!com.rwai.agent.logic.ProfileTemplates.isValidKey(k) || com.rwai.agent.logic.ProfileTemplates.looksLikeCard(v)) rejected += k
            else j.put(k, v.take(500))
        }
        if (about.isNotBlank()) j.put(ABOUT, about.trim().take(600))
        if (j.length() == 0) secrets.remove(NAME) else secrets.put(NAME, j.toString())
        return rejected
    }

    companion object {
        private const val NAME = "owner_profile"
        private const val ABOUT = "_about"
    }
}
