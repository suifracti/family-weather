package org.breezyweather.domain.subtitle.local

import java.net.URI
import java.time.LocalDate
import org.breezyweather.domain.subtitle.model.*

/** Presentation over V5 literal fields. It never edits the transcript or supplies missing facts. */
object BriefWeatherNotes {
    private const val NUMBER = "[零〇一二两三四五六七八九十百\\d]+"
    private val places = Regex("内蒙古|黑龙江|吉林|辽宁|北京|天津|河北|山西|山东|河南|陕西|甘肃|宁夏|青海|新疆|西藏|四川|重庆|贵州|云南|湖北|湖南|安徽|江苏|浙江|江西|福建|广东|广西|海南|台湾|香港|澳门|南方|北方|东北|华北|西北|西南|江南|江淮|黄淮|全国|我国|沿海地区|哈尔滨|长春|沈阳|呼和浩特|乌鲁木齐|银川|西宁|兰州|西安|太原|石家庄|济南|郑州|合肥|南京|上海|武汉|长沙|南昌|杭州|福州|台北|广州|南宁|海口|贵阳|昆明|成都|拉萨")
    private const val FROZEN_MEDIA = "463caa62875f2d89ee160dec89fd4cd28b68e962dc4653b820ad27a32eb311fe"
    private const val FROZEN_VTT = "cd4ac5eebc8cf5c008551272e48f2872a29ec42b378fd733c5807944409905a0"
    const val SNOW_REVIEW = "雪花句的否定关系待核"
    const val PERIOD_REVIEW = "预报时间及“首次”关系待核"

    // These are documented conflicts in this exact preserved artifact, not acoustic corrections.
    private fun frozen(summary: EpisodeSummary) = summary.sourceVideoSha256 == FROZEN_MEDIA &&
        summary.subtitleVttSha256 == FROZEN_VTT
    fun hasSnowConflict(summary: EpisodeSummary, item: EpisodeSummaryItem) = frozen(summary) &&
        item.sourceText?.contains("内蒙古东部的部分地区还会有大雨局地可能会飘落雪花") == true
    fun hasPeriodConflict(summary: EpisodeSummary, item: EpisodeSummaryItem) = frozen(summary) &&
        item.sourceText?.contains("五号到七号涂上大片区域") == true &&
        item.sourceText.contains("福州还有南宁") && item.sourceText.contains("首次最低气温")

    /** The existing official programme identity must also agree with the media's dated path. */
    fun verifiedDateContext(manifest: SubtitleManifest): Boolean {
        if (manifest.program != "CHINA_WEATHER_LIANBO" ||
            manifest.sourceIdentity?.matches(Regex("weather_com_cn\\|CHINA_WEATHER_LIANBO\\|3M\\|\\d+")) != true) return false
        val date = runCatching { LocalDate.parse(manifest.episodeDate) }.getOrNull() ?: return false
        val uri = runCatching { URI(manifest.sourceVideoUrl) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host != "vod.weathertv.cn") return false
        val path = Regex("/video/(\\d{4})/(\\d{1,2})/(\\d{1,2})/[^/]+\\.mp4").matchEntire(uri.path) ?: return false
        return path.groupValues[1].toInt() == date.year && path.groupValues[2].toInt() == date.monthValue &&
            path.groupValues[3].toInt() == date.dayOfMonth
    }

    fun dateLabel(raw: String, episodeDate: String, verified: Boolean): String {
        if (!verified) return raw
        val date = runCatching { LocalDate.parse(episodeDate) }.getOrNull() ?: return raw
        if (raw in setOf("今天晚上到明天", "今晚到明天", "今夜到明天")) {
            val next = date.plusDays(1)
            return if (next.month == date.month) "${date.monthValue}月${date.dayOfMonth}日晚至${next.dayOfMonth}日" else raw
        }
        val relative = Regex("^(今天|今日|明天|明日|后天|今晚|今夜)(早晨|夜间|白天)?$").matchEntire(raw)
        if (relative != null) {
            val offset = when (relative.groupValues[1]) { "明天", "明日" -> 1L; "后天" -> 2L; else -> 0L }
            val value = date.plusDays(offset)
            if (value.month != date.month || value.year != date.year) return raw
            val suffix = if (relative.groupValues[1] in setOf("今晚", "今夜")) "晚" else relative.groupValues[2]
            return "${value.monthValue}月${value.dayOfMonth}日$suffix"
        }
        val days = Regex("^(?:($NUMBER)月)?($NUMBER)(?:日|号)(?:(?:到|至|～|—|-)?(?:($NUMBER)月)?($NUMBER)(?:日|号))?$").matchEntire(raw) ?: return raw
        val month = if (days.groupValues[1].isEmpty()) date.monthValue else number(days.groupValues[1]) ?: return raw
        val endMonth = if (days.groupValues[3].isEmpty()) month else number(days.groupValues[3]) ?: return raw
        val first = number(days.groupValues[2]) ?: return raw
        val last = days.groupValues[4].takeIf { it.isNotEmpty() }?.let(::number) ?: first
        // Do not roll an earlier day into next month, or invent a cross-month/year range.
        if (month != date.monthValue || endMonth != month || first < date.dayOfMonth ||
            last < first || first !in 1..date.lengthOfMonth() || last > date.lengthOfMonth()) return raw
        return "$month" + "月$first" + if (last == first) "日" else "–${last}日"
    }

    private fun number(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if ('十' in raw) {
            val parts = raw.split('十')
            if (parts.size != 2 || parts.any { it.length > 1 }) return null
            val tens = if (parts[0].isEmpty()) 1 else digits[parts[0].single()] ?: return null
            val units = if (parts[1].isEmpty()) 0 else digits[parts[1].single()] ?: return null
            return tens * 10 + units
        }
        return raw.singleOrNull()?.let(digits::get)
    }

    fun completeNumbers(raw: String): Boolean = Regex("$NUMBER").findAll(raw).all { number(it.value) != null }

    private fun region(raw: String): String {
        var value = raw.replace("还有", "、").replace("以及", "、").replace("的部分地区", "部分地区")
        val matches = places.findAll(value).toList()
        for ((a, b) in matches.zipWithNext().toList().asReversed()) {
            if (a.range.last + 1 == b.range.first) value = value.substring(0, b.range.first) + "、" + value.substring(b.range.first)
        }
        return value
    }

    private fun weather(raw: String): String {
        var value = raw
        val replacements = listOf("仍然会有大范围的降雨" to "大范围降雨", "将会刮起" to "有",
            "会先后出现" to "先后有", "将会有" to "有", "还会有" to "有",
            "降水也将会发展增多" to "降水增多", "降水将会明显的收敛" to "降水明显减少",
            "将会降温" to "降温", "将会升温" to "升温", "甚至是" to "，甚至")
        replacements.forEach { (from, to) -> value = value.replace(from, to) }
        value = value.replace(Regex("($NUMBER)(到|至|～|—|-)($NUMBER)(摄氏度|度|℃|级的|级)")) { m ->
            val a = number(m.groupValues[1]); val b = number(m.groupValues[3])
            if (a == null || b == null) m.value else "$a–$b" + if (m.groupValues[4].startsWith("级")) "级" else "℃"
        }
        value = value.replace(Regex("($NUMBER)(摄氏度|度|℃)")) { m -> number(m.groupValues[1])?.let { "$it℃" } ?: m.value }
        return value
    }

    fun display(summary: EpisodeSummary, item: EpisodeSummaryItem, review: Boolean = false): String {
        if (SNOW_REVIEW in item.reviewReasons) return "内蒙古东部部分地区：雪花句的否定关系待核。"
        if (PERIOD_REVIEW in item.reviewReasons) return "福州、南宁最低气温将低于20℃。"
        if (item.weather == null || item.region == null || !completeNumbers(item.weather.value)) return "${item.region?.let { region(it.value) } ?: "这段转写"}：字段不完整，请回看原句。"
        val forecasts = listOf(SummaryForecastEvidence(item.region, item.time ?: SummaryEvidenceField("", 0, 0), item.weather, item.advice)) + item.relatedForecasts
        val clauses = forecasts.map { f ->
            val rawWeather = if (!review && hasSnowConflict(summary, item)) f.weather.value.substringBefore("局地可能会飘落雪花") else f.weather.value
            region(f.region.value) + weather(rawWeather) + (f.advice?.let { "；${it.value}" } ?: "")
        }
        val time = if (review) "" else item.time?.let { dateLabel(it.value, summary.episodeDate, summary.dateContextVerified) + "，" }.orEmpty()
        if (clauses.size > 1 && forecasts.first().weather.value.contains("风")) {
            return time + clauses.first() + "。\n" + clauses.drop(1).joinToString("；") + "。"
        }
        return time + clauses.joinToString("；") + "。"
    }

    fun present(summary: EpisodeSummary, manifest: SubtitleManifest): EpisodeSummary {
        val context = summary.copy(dateContextVerified = verifiedDateContext(manifest))
        fun missingNumber(item: EpisodeSummaryItem) = item.copy(weather = null, relatedForecasts = emptyList(),
            reviewReasons = listOf("温度或风力数值识别不完整"))
        fun complete(item: EpisodeSummaryItem) = (listOfNotNull(item.weather) + item.relatedForecasts.map { it.weather })
            .all { completeNumbers(it.value) }
        val reviews = context.reviewItems.map { if (complete(it)) it else missingNumber(it) }.toMutableList()
        val main = context.items.filter { item ->
            if (!complete(item)) {
                reviews += missingNumber(item); false
            } else if (hasPeriodConflict(context, item)) {
                reviews += item.copy(reviewReasons = listOf(PERIOD_REVIEW)); false
            } else {
                if (hasSnowConflict(context, item)) reviews += item.copy(reviewReasons = listOf(SNOW_REVIEW))
                true
            }
        }
        return context.copy(items = main.map { it.copy(text = display(context, it)) },
            reviewItems = reviews.sortedBy { it.startMs }.distinctBy { listOf(it.sourceText, it.reviewReasons) }
                .map { it.copy(text = display(context, it, review = true)) })
    }
}
