package com.rwai.agent.connectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.rwai.agent.logic.GeoPlace
import com.rwai.agent.logic.WeatherFormat
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Погода от Open-Meteo (без ключа). Наружу уходят только округлённые координаты или название города. */
object WeatherClient {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).header("User-Agent", "RusyaAI").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Сервис погоды ответил ${r.code}.")
            return r.body?.string().orEmpty()
        }
    }

    fun geocode(city: String): GeoPlace? =
        WeatherFormat.parseGeocode(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" + URLEncoder.encode(city, "UTF-8")))

    fun forecast(placeName: String, lat: Double, lon: Double): String {
        // Округляем до сотых (≈1 км): точный адрес наружу не уходит.
        val la = String.format(Locale.US, "%.2f", lat)
        val lo = String.format(Locale.US, "%.2f", lon)
        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&timezone=auto&forecast_days=3&wind_speed_unit=ms" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        )
        val t = WeatherFormat.format(placeName, json)
        if (t.isEmpty()) throw java.io.IOException("Не удалось разобрать ответ сервиса погоды.")
        return t
    }
}

/** Приблизительное местоположение телефона (городской уровень) — только по разрешению владельца. */
object LocationFinder {
    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun find(c: Context): Location? {
        if (!hasPermission(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = try { lm.getProviders(true) } catch (_: Exception) { emptyList() }
        val last = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3_600_000L) return last
        if (Build.VERSION.SDK_INT >= 30) {
            val p = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER).firstOrNull { it in providers }
            if (p != null) {
                val fresh = withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val cancel = android.os.CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        try {
                            lm.getCurrentLocation(p, cancel, ContextCompat.getMainExecutor(c)) { cont.resume(it) }
                        } catch (_: Exception) { cont.resume(null) }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return last
    }

    /** Название населённого пункта по координатам, если служба геокодирования доступна. */
    @Suppress("DEPRECATION")
    fun placeName(c: Context, loc: Location): String? = try {
        if (!Geocoder.isPresent()) null else {
            val a = Geocoder(c, Locale("ru")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            (a?.locality ?: a?.subAdminArea ?: a?.adminArea)?.take(60)
        }
    } catch (_: Exception) { null }
}
