package com.rwai.agent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRouteTest {
    private val speaker = AudioDev(1, "speaker", "", "Speaker")
    private val earpiece = AudioDev(2, "earpiece", "", "Earpiece")
    private val car = AudioDev(3, "bt_sco", "AA:01", "My Car", CallRoute.CLASS_CAR_AUDIO)
    private val buds = AudioDev(4, "bt_sco", "AA:02", "Galaxy Buds2 Pro", CallRoute.CLASS_HEADSET)
    private val wired = AudioDev(5, "wired", "", "Headphones")
    private val unknownBt = AudioDev(6, "bt_sco", "AA:03", "XYZ-77", CallRoute.CLASS_HANDSFREE)

    @Test fun carIsRecognizedByClassAndByName() {
        assertEquals(DeviceKind.CAR, CallRoute.kindOf(car))
        assertEquals(DeviceKind.CAR, CallRoute.kindOf(AudioDev(7, "bt_sco", "AA:07", "Toyota Hands-Free", CallRoute.CLASS_HANDSFREE)))
        assertEquals(DeviceKind.CAR, CallRoute.kindOf(AudioDev(8, "bt_sco", "AA:08", "Android Auto", null)))
    }

    @Test fun headsetsAreRecognized() {
        assertEquals(DeviceKind.HEADSET, CallRoute.kindOf(buds))
        assertEquals(DeviceKind.HEADSET, CallRoute.kindOf(wired))
        assertEquals(DeviceKind.HEADSET, CallRoute.kindOf(AudioDev(9, "bt_sco", "AA:09", "WH-1000XM5", CallRoute.CLASS_HANDSFREE)))
        assertEquals(DeviceKind.HEADSET, CallRoute.kindOf(AudioDev(10, "ble_headset", "AA:10", "LE", null)))
    }

    @Test fun unknownBluetoothDeviceIsNeverTreatedAsAHeadset() {
        assertEquals(DeviceKind.OTHER, CallRoute.kindOf(unknownBt))
    }

    @Test fun ownerOverrideBeatsGuessing() {
        assertEquals(DeviceKind.HEADSET, CallRoute.kindOf(unknownBt, DeviceKind.HEADSET))
        assertEquals(DeviceKind.CAR, CallRoute.kindOf(buds, DeviceKind.CAR))
    }

    @Test fun headsetWinsOverCarAndSpeaker() {
        val c = CallRoute.choose(listOf(speaker, earpiece, car, buds), emptyMap(), RouteFallback.SPEAKER)
        assertEquals(4, c.deviceId)
        assertEquals(DeviceKind.HEADSET, c.kind)
    }

    @Test fun withoutHeadsetTheCarIsSkippedAndPhoneSpeakerIsUsed() {
        val c = CallRoute.choose(listOf(speaker, earpiece, car), emptyMap(), RouteFallback.SPEAKER)
        assertEquals(1, c.deviceId)
        assertEquals(DeviceKind.SPEAKER, c.kind)
        val e = CallRoute.choose(listOf(speaker, earpiece, car), emptyMap(), RouteFallback.EARPIECE)
        assertEquals(2, e.deviceId)
    }

    @Test fun carAndUnknownDevicesAreNeverChosen() {
        val c = CallRoute.choose(listOf(car, unknownBt), emptyMap(), RouteFallback.SPEAKER)
        assertNull(c.deviceId)
    }

    @Test fun wiredHeadsetBeatsBluetoothOne() {
        val c = CallRoute.choose(listOf(buds, wired, speaker), emptyMap(), RouteFallback.SPEAKER)
        assertEquals(5, c.deviceId)
    }

    @Test fun overridesAreAppliedByAddress() {
        val c = CallRoute.choose(listOf(speaker, unknownBt), mapOf("AA:03" to DeviceKind.HEADSET), RouteFallback.SPEAKER)
        assertEquals(6, c.deviceId)
    }

    @Test fun reconnectStormIsDebounced() {
        assertFalse(CallRoute.ready(1_000, 1_800))
        assertTrue(CallRoute.ready(1_000, 2_600))
    }
}
