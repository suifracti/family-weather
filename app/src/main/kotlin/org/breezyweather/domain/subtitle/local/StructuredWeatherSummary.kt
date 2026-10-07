package org.breezyweather.domain.subtitle.local

import org.breezyweather.domain.multisource.model.TimestampedCue
import org.breezyweather.domain.subtitle.gate.EpisodeSummaryGate
import org.breezyweather.domain.subtitle.model.*

/** Bounded literal assertions: no guessed facts, acoustic corrections, or inferred advice. */
object StructuredWeatherSummary {
    private const val NUMBER = "[零〇一二两三四五六七八九十百\\d]+"
    private const val RANGE = "$NUMBER(?:到|至|～|—|-)$NUMBER"
    private val region = Regex("(?:内蒙古|黑龙江|吉林|辽宁|北京|天津|河北|山西|山东|河南|陕西|甘肃|宁夏|青海|新疆|西藏|四川|重庆|贵州|云南|湖北|湖南|安徽|江苏|浙江|江西|福建|广东|广西|海南|台湾|香港|澳门|南方|北方|东北|华北|西北|西南|江南|江淮|黄淮|全国|我国|沿海地区|哈尔滨|长春|沈阳|呼和浩特|乌鲁木齐|银川|西宁|兰州|西安|太原|石家庄|济南|郑州|合肥|南京|上海|武汉|长沙|南昌|杭州|福州|台北|广州|南宁|海口|贵阳|昆明|成都|拉萨)(?:东北部|西北部|东南部|西南部|中西部|中东部|东部|西部|南部|北部|中部|盆地)?(?:(?:的)?(?:大部分地区|部分地区|多地|一带|等地|地区)){0,2}")
    private val time = Regex("(?:${NUMBER}月)?$NUMBER(?:日|号)(?:(?:到|至|～|—|-)?(?:${NUMBER}月)?$NUMBER(?:日|号))?(?:前后)?|(?:今天晚上|今日夜间|今天|今晚|今夜)(?:到|至)(?:明天|明日|后天)|(?:明天|明日|后天|今晚|今夜|今天|今日|未来几天|假期后半段|假期的后半段)(?:早晨|夜间|白天)?")
    private const val TEMP_UNIT = "(?:摄氏度|度|℃)"
    private const val SKY = "(?:多云|晴天|晴|阴天|阴|小雨|中雨|大雨|雷阵雨|阵雨|雨夹雪|小雪|大雪)"
    private val weather = Regex("(?:不会|没有|无|不)?(?:仍然|还|将会|将|会|有|出现|先后|可能|局地|部分地区|大范围的|甚至是)*" +
        "(?:降温(?:$RANGE$TEMP_UNIT)?|升温(?:$RANGE$TEMP_UNIT)?|(?:刮起)?$RANGE(?:级|级的)风|" +
        "(?:是今年下半年以来首次)?(?:最低|最高)气温(?:来到|降至|升至|达到|低于|高于)$NUMBER$TEMP_UNIT(?:以下|以上)?|" +
        "(?:降水|气温)(?:将会|将|会|也|进一步|明显|发展|的)*(?:增多|减少|下降|上升|升高|降低|收敛)|" +
        "(?:飘落)?雪花|大到暴雨|大暴雨|暴雨|降雨|雨水|降雪|大风|高温|" +
        "$SKY(?:转$SKY)?(?:$RANGE$TEMP_UNIT)?)")
    // Only the promotional clause is excluded. A later forecast needs its own new time anchor.
    private val promotion = Regex("(?:扫描|扫码)(?:屏幕上方|屏幕|上方)?(?:二维码)?|二维码|关注公众号|下载.{0,5}(?:客户端|软件|APP)|了解更多天气信息|(?:下面|最后|咱们|我们)(?:来)?(?:关注|来看)(?:具体的)?城市天气预报|城市天气预报")
    private val transition = Regex("另外随着|此外随着|气温方面|降温方面|不过|之后|随后|届时|[。；！？]")
    private val connector = Regex("^(?:[，、和与及]|还有|以及|到|至|等地|的|部分地区)*$")
    private val prefix = Regex("^(?:[，、：]|都|也|还|将|会|有|预计|仍然|先后|局地|部分地区|的)*$")
    private val advice = Regex("(?:注意|需防范|需要防范|务必|建议|警惕)(?:[^，。；！？]{2,24})(?=[，。；！？]|$)")

    private data class Forecast(val anchor: Int, val region: SummaryEvidenceField,
                                val time: SummaryEvidenceField, val weather: SummaryEvidenceField,
                                val advice: SummaryEvidenceField? = null) {
        val end get() = advice?.end ?: weather.end
        val signature get() = listOf(region.value, time.value, weather.value, advice?.value)
    }

    fun display(item: EpisodeSummaryItem): String {
        if (item.relatedForecasts.isNotEmpty()) {
            val forecasts = listOf(SummaryForecastEvidence(item.region!!, item.time!!, item.weather!!, item.advice)) + item.relatedForecasts
            return "时间：${item.time.value}；" + forecasts.joinToString("；") {
                "${it.region.value}：${it.weather.value}" + (it.advice?.let { a -> "；注意：${a.value}" } ?: "")
            }
        }
        return buildList {
            item.region?.let { add("地区：${it.value}") }
            item.time?.let { add("时间：${it.value}") }
            item.weather?.let { add("天气：${it.value}") }
            item.advice?.let { add("注意：${it.value}") }
        }.joinToString("；")
    }

    fun generate(manifest: SubtitleManifest, cues: List<TimestampedCue>): EpisodeSummary? {
        if (cues.isEmpty() || cues.any { it.endMs > manifest.durationMs } ||
            cues.zipWithNext().any { (a, b) -> a.endMs > b.startMs }) return null
        val full = cues.joinToString("") { it.normalizedText }
        val offsets = mutableListOf<Int>(); var offset = 0
        cues.forEach { offsets += offset; offset += it.normalizedText.length }
        val promos = promotion.findAll(full).toList()
        val boundaries = (listOf(0, full.length) + transition.findAll(full).map { it.range.first } +
            promos.flatMap { listOf(it.range.first, it.range.last + 1) } +
            cues.indices.drop(1).filter { cues[it].startMs - cues[it - 1].endMs >= 2000 }.map { offsets[it] })
            .distinct().sorted()
        val forecasts = mutableListOf<Forecast>()
        val reviewItems = mutableListOf<EpisodeSummaryItem>()
        val omissions = linkedSetOf<String>()
        fun label(at: Int, place: String) = "${formatTime(cues[offsets.indexOfLast { it <= at }.coerceAtLeast(0)].startMs)} $place"
        fun review(begin: Int, end: Int, place: SummaryEvidenceField, reason: String,
                   weatherField: SummaryEvidenceField? = null, timeField: SummaryEvidenceField? = null) {
            val ids = cues.indices.filter { offsets[it] < end && offsets[it] + cues[it].normalizedText.length > begin }
            if (ids.isEmpty() || end - begin > 140 || cues[ids.last()].endMs - cues[ids.first()].startMs > 30000) return
            fun relative(f: SummaryEvidenceField) = f.copy(start = f.start - begin, end = f.end - begin)
            reviewItems += EpisodeSummaryItem(cues[ids.first()].startMs, cues[ids.last()].endMs, "待核片段", ids,
                sourceText = full.substring(begin, end), sourceOffset = begin - offsets[ids.first()],
                region = relative(place), time = timeField?.let(::relative), weather = weatherField?.let(::relative),
                reviewReasons = listOf(reason))
        }
        for ((left, right) in boundaries.zipWithNext()) {
            if (promos.any { left >= it.range.first && right <= it.range.last + 1 }) continue
            val window = full.substring(left, right)
            val places = region.findAll(window).toList()
            var index = 0
            while (index < places.size) {
                val first = places[index]; var last = first; index++
                while (index < places.size && connector.matches(window.substring(last.range.last + 1, places[index].range.first))) {
                    last = places[index++]
                }
                val end = if (index < places.size) places[index].range.first else window.length
                val tailStart = last.range.last + 1
                val tail = window.substring(tailStart, end)
                val risk = label(left + first.range.first, first.value)
                val placeField = SummaryEvidenceField(window.substring(first.range.first, last.range.last + 1),
                    left + first.range.first, left + last.range.last + 1)
                val found = weather.find(tail)
                if (found == null) {
                    if (tail.contains(Regex("级风|气温|降温|升温"))) omissions += "$risk：天气数值或单位识别不完整，已省略"
                    if (tail.contains(Regex("级风|气温|降温|升温"))) review(placeField.start, left + end, placeField, "天气数值或单位识别不完整")
                    continue
                }
                val before = tail.substring(0, found.range.first)
                val suffix = tail.substring(found.range.last + 1)
                if (found.value.endsWith("降温") || found.value.endsWith("升温") ||
                    suffix.startsWith("转") || suffix.startsWith("到") || suffix.startsWith("至")) {
                    omissions += "$risk：天气转换或数值、单位识别不完整，已省略"
                    review(placeField.start, left + end, placeField, "天气转换或温度识别不完整"); continue
                }
                if (!prefix.matches(before)) {
                    omissions += "$risk：地区与天气之间存在未解析内容，已省略"
                    review(placeField.start, left + end, placeField, "地区与天气关系待核"); continue
                }
                val preceding = time.findAll(window.substring(0, first.range.first)).lastOrNull()
                val afterTime = preceding?.let { window.substring(it.range.last + 1, first.range.first) }
                if (afterTime?.startsWith("到") == true || afterTime?.startsWith("至") == true) {
                    omissions += "$risk：时间范围识别不完整，已省略"
                    review(left + preceding!!.range.first, left + end, placeField, "时间范围识别不完整"); continue
                }
                val second = weather.find(tail, found.range.last + 1)?.takeIf {
                    val join = tail.substring(found.range.last + 1, it.range.first)
                    // Empty adjacency may be the next impact sentence, e.g. 暴雨 / 大风和降水会影响秋收.
                    (join.isNotEmpty() && Regex("^(?:并且|并|和|也|还|将|会|有|[，、])+$").matches(join)) ||
                        (join.isEmpty() && it.value.startsWith(Regex("^(?:甚至|局地|可能)")))
                }
                val weatherEnd = second?.range?.last ?: found.range.last
                val weatherValue = tail.substring(found.range.first, weatherEnd + 1)
                if (weatherValue.length > 50) {
                    omissions += "$risk：天气断言过长，已省略"; continue
                }
                fun field(value: String, at: Int) = SummaryEvidenceField(value, at, at + value.length)
                val weatherField = field(weatherValue, left + tailStart + found.range.first)
                if (preceding == null) {
                    review(placeField.start, weatherField.end, placeField, "预报时间待核", weatherField)
                    continue
                }
                val absoluteTime = left + preceding.range.first
                // Only adjacent explicit advice belongs to this forecast; never cross an impact sentence.
                val remaining = tail.substring(weatherEnd + 1)
                val explicitAdvice = advice.find(remaining)?.takeIf { it.range.first <= 1 &&
                    Regex("(?:保暖|安全|路况|积水|排水|风险|灾害|防晒|补水)$").containsMatchIn(it.value) }
                forecasts += Forecast(absoluteTime,
                    field(window.substring(first.range.first, last.range.last + 1), left + first.range.first),
                    field(preceding.value, absoluteTime), weatherField,
                    explicitAdvice?.let { field(it.value, left + tailStart + weatherEnd + 1 + it.range.first) })
            }
        }
        val eligible = forecasts.distinctBy { it.signature }.filter { f ->
            val ids = cues.indices.filter { offsets[it] < f.end && offsets[it] + cues[it].normalizedText.length > f.time.start }
            val low = ids.any { cues[it].confidence?.let { c -> c < 0.6f } == true }
            val bounded = ids.isNotEmpty() && f.end - f.time.start <= 140 && cues[ids.last()].endMs - cues[ids.first()].startMs <= 30000
            if (low || !bounded) omissions += "${label(f.region.start, f.region.value)}：${if (low) "来源词存在低置信度风险" else "字段跨段，无法可靠绑定"}，已省略"
            if (low && bounded) review(f.time.start, f.end, f.region, "来源词存在低置信度风险", f.weather, f.time)
            !low && bounded
        }
        // Keep regional severity separate within the event. Repeated broad and local clauses share one card.
        val groups = mutableListOf<MutableList<Forecast>>()
        for (forecast in eligible) {
            val previous = groups.lastOrNull()
            val ids = cues.indices.filter { offsets[it] < forecast.end && offsets[it] + cues[it].normalizedText.length > forecast.time.start }
            if (previous != null && previous.first().anchor == forecast.anchor && previous.size < 4 &&
                forecast.end - previous.first().time.start <= 140 &&
                cues[ids.last()].endMs - cues[ids.first()].startMs <= 30000) previous += forecast
            else groups += mutableListOf(forecast)
        }
        val items = groups.map { group ->
            val begin = group.first().time.start
            val end = group.maxOf { it.end }
            val ids = cues.indices.filter { offsets[it] < end && offsets[it] + cues[it].normalizedText.length > begin }
            fun relative(f: SummaryEvidenceField) = f.copy(start = f.start - begin, end = f.end - begin)
            val first = group.first()
            val item = EpisodeSummaryItem(cues[ids.first()].startMs, cues[ids.last()].endMs, "", ids,
                sourceText = full.substring(begin, end), sourceOffset = begin - offsets[ids.first()],
                region = relative(first.region), time = relative(first.time), weather = relative(first.weather),
                advice = first.advice?.let(::relative), relatedForecasts = group.drop(1).map {
                    SummaryForecastEvidence(relative(it.region), relative(it.time), relative(it.weather), it.advice?.let(::relative))
                })
            item.copy(text = display(item))
        }
        // Cover distinct changes before filling remaining slots in programme order.
        fun topics(item: EpisodeSummaryItem): Set<String> {
            val value = listOfNotNull(item.weather?.value) + item.relatedForecasts.map { it.weather.value }
            return buildSet {
                if (value.any { Regex("降温|升温|气温").containsMatchIn(it) }) add("temperature")
                if (value.any { "风" in it }) add("wind")
                if (value.any { "暴雨" in it }) add("heavyRain")
                if (value.any { "雪" in it }) add("snow")
                if (value.any { "收敛" in it || "减少" in it }) add("easing")
                if (isEmpty()) add("other")
            }
        }
        val selected = linkedSetOf<EpisodeSummaryItem>(); val covered = mutableSetOf<String>()
        for (item in items) if (selected.size < 6 && topics(item).any { it !in covered }) {
            selected += item; covered += topics(item)
        }
        for (item in items) if (selected.size < 6) selected += item
        val ordered = selected.sortedBy { it.startMs }
        if (ordered.size < items.size) omissions += "部分其他地区原句未列入简短要点；请结合字幕回看。"
        if (ordered.isEmpty()) omissions += "现有转写不足以可靠提取地区、时间和天气；请回看原片。"
        val briefOmissions = omissions.take(3).toMutableList()
        if (omissions.size > briefOmissions.size) briefOmissions +=
            "其他 ${omissions.size - briefOmissions.size} 项缺字段或不能可靠绑定的内容已省略；请结合字幕回看。"
        briefOmissions += "只列原句可绑定字段；缺失或不能可靠绑定的内容不补猜。时间词以节目播出日 ${manifest.episodeDate} 为参照；转写未人工听核。"
        return BriefWeatherNotes.present(EpisodeSummary(manifest.program, manifest.episodeDate, manifest.sourceVideoUrl,
            manifest.sourceVideoSha256, manifest.vttSha256, EpisodeSummaryGate.LOCAL_STRUCTURED_ASR_METHOD,
            ordered, briefOmissions, reviewItems = reviewItems), manifest)
    }

    private fun String.startsWith(regex: Regex) = regex.containsMatchIn(this)
    private fun formatTime(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
}
