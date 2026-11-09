package com.rwai.agent.logic

/**
 * Куда направлять звук голосового звонка. Правило владельца: звонок идёт на наушники, если они надеты, иначе на динамик телефона
 * (или к уху, как выбрано), и никогда на звук машины. Чистая логика без Android: классификация устройств и выбор проверяются тестами.
 */
enum class DeviceKind(val title: String) { HEADSET("наушники"), CAR("машина"), SPEAKER("динамик телефона"), EARPIECE("у уха"), OTHER("не определено") }

enum class RouteFallback(val title: String) { SPEAKER("динамик телефона"), EARPIECE("у уха") }

/** Устройство вывода звука звонка. [type] — один из: wired, usb, hearing_aid, ble_headset, bt_sco, speaker, earpiece, other. */
data class AudioDev(val id: Int, val type: String, val address: String, val name: String, val btClass: Int? = null)

data class RouteChoice(val deviceId: Int?, val kind: DeviceKind, val reason: String)

object CallRoute {
    // Коды класса устройства Bluetooth (BluetoothClass.Device)
    const val CLASS_HEADSET = 0x0404
    const val CLASS_HANDSFREE = 0x0408
    const val CLASS_HEADPHONES = 0x0418
    const val CLASS_PORTABLE_AUDIO = 0x041C
    const val CLASS_CAR_AUDIO = 0x0420

    private val CAR_NAME_HINTS = listOf(
        "car", "auto", "carplay", "android auto", "toyota", "honda", "bmw", "audi", "mercedes", "ford", "kia", "hyundai", "mazda", "nissan",
        "volkswagen", "vw ", "skoda", "peugeot", "renault", "lexus", "subaru", "volvo", "jeep", "tesla", "chevrolet", "suzuki", "citroen", "fiat",
        "магнитол", "авто", "машин",
    )
    private val HEADSET_NAME_HINTS = listOf(
        "buds", "pods", "headphone", "headset", "earbud", "earphone", "наушник", "jbl", "sony", "wh-", "wf-", "bose", "beats", "soundcore", "freebuds", "airdots",
    )

    fun kindOf(d: AudioDev, override: DeviceKind? = null): DeviceKind {
        if (override != null) return override
        val name = d.name.lowercase()
        return when (d.type) {
            "speaker" -> DeviceKind.SPEAKER
            "earpiece" -> DeviceKind.EARPIECE
            "wired", "usb", "hearing_aid" -> DeviceKind.HEADSET
            "ble_headset", "bt_sco" -> when {
                d.btClass == CLASS_CAR_AUDIO -> DeviceKind.CAR
                CAR_NAME_HINTS.any { name.contains(it) } -> DeviceKind.CAR
                d.btClass == CLASS_HEADSET || d.btClass == CLASS_HEADPHONES || d.btClass == CLASS_PORTABLE_AUDIO -> DeviceKind.HEADSET
                HEADSET_NAME_HINTS.any { name.contains(it) } -> DeviceKind.HEADSET
                d.type == "ble_headset" && d.btClass == null -> DeviceKind.HEADSET // наушники LE Audio обычно без класса
                else -> DeviceKind.OTHER
            }
            else -> DeviceKind.OTHER
        }
    }

    private fun priority(d: AudioDev) = when (d.type) { "wired" -> 0; "usb" -> 1; "ble_headset" -> 2; "bt_sco" -> 3; else -> 4 }

    /** Выбор устройства для звонка. Машина и неопределённые устройства не выбираются никогда. */
    fun choose(devices: List<AudioDev>, overrides: Map<String, DeviceKind>, fallback: RouteFallback): RouteChoice {
        val classified = devices.map { it to kindOf(it, overrides[it.address].takeIf { _ -> it.address.isNotBlank() }) }
        val headset = classified.filter { it.second == DeviceKind.HEADSET }.minByOrNull { priority(it.first) }
        if (headset != null) return RouteChoice(headset.first.id, DeviceKind.HEADSET, "наушники «" + headset.first.name + "»")
        val wanted = if (fallback == RouteFallback.SPEAKER) DeviceKind.SPEAKER else DeviceKind.EARPIECE
        val local = classified.firstOrNull { it.second == wanted }
        if (local != null) return RouteChoice(local.first.id, wanted, "наушников нет, " + wanted.title)
        return RouteChoice(null, DeviceKind.OTHER, "нет подходящего устройства (наушников или " + wanted.title + ")")
    }

    /** Переподключение машины (Android Auto) шлёт десятки событий: реагируем не чаще, чем раз в [gapMs]. */
    fun ready(lastTs: Long, now: Long, gapMs: Long = 1500L): Boolean = now - lastTs >= gapMs
}
