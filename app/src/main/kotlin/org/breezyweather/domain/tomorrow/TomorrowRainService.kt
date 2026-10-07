/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.tomorrow

import android.content.Context
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.breezyweather.BuildConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class TomorrowRainService @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private var cachedData: TomorrowRainData? = null
    private var cachedLocationKey: String? = null
    private var lastFetchTime: Long = 0

    fun getApiKey(context: Context): String {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val customKey = sp.getString(KEY_QWEATHER_KEY, "") ?: ""
        if (customKey.isNotEmpty()) {
            return customKey
        }
        return BuildConfig.QWEATHER_KEY
    }

    fun setApiKey(context: Context, key: String) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_QWEATHER_KEY, key.trim()).apply()
        cachedData = null // Invalidate cache on key change
    }

    fun getTomorrowRain(
        context: Context,
        latitude: Double,
        longitude: Double,
        forceRefresh: Boolean = false
    ): Observable<TomorrowRainData> {
        val now = System.currentTimeMillis()
        val locKey = org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer.toCanonicalLocationId(latitude, longitude)

        if (!forceRefresh && cachedData != null && cachedLocationKey == locKey && (now - lastFetchTime < 15 * 60 * 1000)) {
            return Observable.just(cachedData!!)
        }

        return Observable.fromCallable {
            val apiKey = getApiKey(context)
            if (apiKey.isEmpty()) {
                val unconfigured = TomorrowRainData(
                    date = "",
                    dayWeatherText = "--",
                    dayWeatherIcon = "",
                    nightWeatherText = "--",
                    nightWeatherIcon = "",
                    dayPrecipAmountMm = 0.0,
                    dayMaxPop = 0,
                    nightPrecipAmountMm = 0.0,
                    nightMaxPop = 0,
                    total24hPrecipMm = 0.0,
                    peakIntensityMmH = 0.0,
                    peakRainWindow = "需配置和风天气 Key",
                    hourlyBreakdown = emptyList(),
                    riskLevel = RainRiskLevel.LOW,
                    updateTime = "",
                    isConfigured = false,
                    errorMessage = "未配置和风天气 API Key"
                )
                return@fromCallable unconfigured
            }

            val shZone = TimeZone.getTimeZone("Asia/Shanghai")
            val tomorrowMillis = now + 86400000L
            val tomorrowDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = shZone }
                .format(Date(tomorrowMillis))

            // QWeather expects location=longitude,latitude (serialized via WeatherCoordinateSerializer)
            val locParam = org.breezyweather.domain.multisource.location.WeatherCoordinateSerializer.toQWeatherLocationParam(latitude, longitude)

            // 1. Fetch 3-day forecast
            val url3d = "https://devapi.qweather.com/v7/weather/3d?location=$locParam&key=$apiKey"
            val req3d = Request.Builder().url(url3d).build()
            val resp3d = okHttpClient.newCall(req3d).execute()
            if (!resp3d.isSuccessful) {
                throw RuntimeException("QWeather 3d HTTP ${resp3d.code}")
            }
            val body3dStr = resp3d.body.string()
            val json3d = JSONObject(body3dStr)
            val code3d = json3d.optString("code")
            if (code3d != "200") {
                throw RuntimeException("QWeather 3d error code: $code3d")
            }
            val updateTime = json3d.optString("updateTime", "")

            var dayWeatherText = "多云"
            var dayWeatherIcon = "101"
            var nightWeatherText = "多云"
            var nightWeatherIcon = "151"
            var dailyPrecipMm = 0.0

            val dailyArr = json3d.optJSONArray("daily")
            if (dailyArr != null) {
                for (i in 0 until dailyArr.length()) {
                    val item = dailyArr.getJSONObject(i)
                    if (item.optString("fxDate") == tomorrowDateStr || (i == 1 && dayWeatherText == "多云")) {
                        dayWeatherText = item.optString("textDay", "多云")
                        dayWeatherIcon = item.optString("iconDay", "101")
                        nightWeatherText = item.optString("textNight", "多云")
                        nightWeatherIcon = item.optString("iconNight", "151")
                        dailyPrecipMm = item.optString("precip", "0.0").toDoubleOrNull() ?: 0.0
                        break
                    }
                }
            }

            // 2. Fetch 24-hour forecast
            val url24h = "https://devapi.qweather.com/v7/weather/24h?location=$locParam&key=$apiKey"
            val req24h = Request.Builder().url(url24h).build()
            val resp24h = okHttpClient.newCall(req24h).execute()
            var hourlyList: MutableList<TomorrowHourlyPoint> = mutableListOf()
            var dayPrecipSum = 0.0
            var dayMaxPop = 0
            var nightPrecipSum = 0.0
            var nightMaxPop = 0
            var peakIntensity = 0.0
            var maxHourlyPop = 0

            if (resp24h.isSuccessful) {
                val body24hStr = resp24h.body.string()
                val json24h = JSONObject(body24hStr)
                if (json24h.optString("code") == "200") {
                    val hourlyArr = json24h.optJSONArray("hourly")
                    if (hourlyArr != null) {
                        for (i in 0 until hourlyArr.length()) {
                            val hItem = hourlyArr.getJSONObject(i)
                            val fxTime = hItem.optString("fxTime") // e.g. 2026-09-18T14:00+08:00
                            val precip = hItem.optString("precip", "0.0").toDoubleOrNull() ?: 0.0
                            val pop = hItem.optString("pop", "0").toIntOrNull() ?: 0
                            val text = hItem.optString("text", "")
                            val icon = hItem.optString("icon", "")

                            // Parse hour
                            var hourText = ""
                            var isTomorrow = false
                            var hourInt = -1
                            if (fxTime.length >= 16) {
                                val datePart = fxTime.substring(0, 10)
                                hourText = fxTime.substring(11, 16)
                                hourInt = fxTime.substring(11, 13).toIntOrNull() ?: -1
                                isTomorrow = datePart == tomorrowDateStr
                            }

                            if (isTomorrow) {
                                hourlyList.add(
                                    TomorrowHourlyPoint(
                                        hourText = hourText,
                                        precipMm = precip,
                                        intensityMmH = precip,
                                        pop = pop,
                                        weatherText = text,
                                        iconCode = icon
                                    )
                                )

                                peakIntensity = max(peakIntensity, precip)
                                maxHourlyPop = max(maxHourlyPop, pop)

                                if (hourInt in 8..19) {
                                    dayPrecipSum += precip
                                    dayMaxPop = max(dayMaxPop, pop)
                                } else {
                                    nightPrecipSum += precip
                                    nightMaxPop = max(nightMaxPop, pop)
                                }
                            }
                        }
                    }
                }
            }

            // Total precip is either daily reported or sum of hours
            val total24hPrecip = max(dailyPrecipMm, dayPrecipSum + nightPrecipSum)
            val peakRainWindow = TomorrowRainRuleEngine.findPeakRainWindow(hourlyList)
            val risk = TomorrowRainRuleEngine.evaluateRiskLevel(
                total24hPrecipMm = total24hPrecip,
                maxPop = max(maxHourlyPop, max(dayMaxPop, nightMaxPop)),
                peakIntensityMmH = peakIntensity,
                dayWeatherText = dayWeatherText,
                nightWeatherText = nightWeatherText
            )

            // Format clean update time (e.g. 17:30)
            val cleanUpdateTime = if (updateTime.length >= 16) {
                updateTime.substring(11, 16)
            } else {
                updateTime
            }

            val result = TomorrowRainData(
                date = tomorrowDateStr,
                dayWeatherText = dayWeatherText,
                dayWeatherIcon = dayWeatherIcon,
                nightWeatherText = nightWeatherText,
                nightWeatherIcon = nightWeatherIcon,
                dayPrecipAmountMm = if (dayPrecipSum > 0.0) dayPrecipSum else dailyPrecipMm * 0.6,
                dayMaxPop = dayMaxPop,
                nightPrecipAmountMm = if (nightPrecipSum > 0.0) nightPrecipSum else dailyPrecipMm * 0.4,
                nightMaxPop = nightMaxPop,
                total24hPrecipMm = total24hPrecip,
                peakIntensityMmH = peakIntensity,
                peakRainWindow = peakRainWindow,
                hourlyBreakdown = hourlyList,
                riskLevel = risk,
                updateTime = cleanUpdateTime,
                isConfigured = true,
                errorMessage = null
            )

            cachedData = result
            cachedLocationKey = locKey
            lastFetchTime = now
            result
        }.subscribeOn(Schedulers.io())
    }

    companion object {
        const val PREF_NAME = "tomorrow_rain_pref"
        const val KEY_QWEATHER_KEY = "qweather_key"

        @Volatile
        private var INSTANCE: TomorrowRainService? = null

        fun getInstance(context: Context): TomorrowRainService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TomorrowRainService(
                    OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                ).also { INSTANCE = it }
            }
        }
    }
}
