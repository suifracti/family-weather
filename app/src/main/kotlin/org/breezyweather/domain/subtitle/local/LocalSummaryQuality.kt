package org.breezyweather.domain.subtitle.local

/** Conservative checks for common hallucinations; ambiguous ASR must fail rather than be repaired. */
object LocalSummaryQuality {
    private val weather = Regex("降水|雨|雪|风|冷|热|温|晴|阴|旱|霜|天气")
    private val places = "北京 天津 河北 山西 内蒙古 辽宁 吉林 黑龙江 上海 江苏 浙江 安徽 福建 江西 山东 河南 湖北 湖南 广东 广西 海南 重庆 四川 贵州 云南 西藏 陕西 甘肃 青海 宁夏 新疆 台湾 香港 澳门 华北 东北 西北 华东 华中 华南 西南 江南 黄淮 江淮 长江 北方 南方".split(' ')
    private val timeWords = mapOf("明晨" to listOf("明晨", "明天早晨"), "今晨" to listOf("今晨", "今天早晨"),
        "明晚" to listOf("明晚", "明天晚上"), "今夜" to listOf("今夜", "今晚"),
        "今晚" to listOf("今晚", "今夜"), "后天" to listOf("后天"), "明天" to listOf("明天"))
    private val risks = listOf("山洪", "地质灾害", "内涝", "洪水", "滑坡", "雷暴", "龙卷", "暴雪", "台风")
    private val numbers = Regex("[零〇一二两三四五六七八九十百]+(?=月|日|号|度|级|摄氏度)")
    private val facts = Regex("\\d+(?:摄氏度|月|日|度|级)")

    fun rejection(text: String, evidence: String): String? {
        val output = compact(text)
        val source = compact(evidence)
        if (output.length !in 8..120 || !weather.containsMatchIn(output)) return "Empty or non-weather output"
        if (source == output || (source.length >= 20 && output.contains(source))) return "Whole evidence copied without condensation"
        if (Regex("(.{3,20})\\1\\1").containsMatchIn(output)) return "Repetitive or degenerate output"
        if (places.any { output.contains(it) && !source.contains(it) }) return "Region absent from supporting cues"
        if (risks.any { output.contains(it) && !source.contains(it) }) return "Risk absent from supporting cues"
        if (timeWords.any { (word, aliases) -> output.contains(word) && aliases.none { source.contains(it) } }) return "Time narrowed or invented"
        if (risks.any { output.contains(it) } && Regex("防范|警惕|可能|风险|预防|谨防").containsMatchIn(source) &&
            !Regex("防范|警惕|可能|风险|预防|谨防").containsMatchIn(output)) return "Risk changed into an occurrence"
        val normalizedSource = normalizeNumbers(source)
        val normalizedOutput = normalizeNumbers(output)
        val ends = Regex("(?:持续|延续)(?:到|至)((?:\\d+月)?\\d+日)").findAll(normalizedSource)
        for (end in ends) {
            val peakAtEnd = Regex("(?:集中|最强|较强|高峰)(?:在|于)?" + Regex.escape(end.groupValues[1]) + "(?:前后)?")
            if (peakAtEnd.containsMatchIn(normalizedOutput) && !peakAtEnd.containsMatchIn(normalizedSource)) {
                return "Rain duration boundary changed into peak timing"
            }
        }
        if (output.contains("防洪") && !source.contains("洪")) return "Flood risk absent from supporting cues"
        val supportedFacts = facts.findAll(normalizedSource).map { it.value }.toSet()
        if (facts.findAll(normalizeNumbers(output)).any { it.value !in supportedFacts }) return "Number or date absent from supporting cues"
        if (output.contains("降温") && !Regex("降温|冷空气|最冷|气温下降").containsMatchIn(source)) return "Cooling absent from supporting cues"
        if (output.contains("高温") && !Regex("高温|闷热|炎热").containsMatchIn(source)) return "Heat absent from supporting cues"
        if (output.contains("降雨") && !Regex("雨|降水").containsMatchIn(source)) return "Rain absent from supporting cues"
        return null
    }

    private fun compact(value: String) = value.replace(Regex("[\\s\\p{P}]+"), "")
    private fun normalizeNumbers(value: String): String = numbers.replace(value) { match ->
        val digits = "零一二三四五六七八九"
        var total = 0; var digit = 0
        match.value.replace('两', '二').replace('〇', '零').forEach { char ->
            when (char) {
                '十' -> { total += (if (digit == 0) 1 else digit) * 10; digit = 0 }
                '百' -> { total += (if (digit == 0) 1 else digit) * 100; digit = 0 }
                else -> digit = digits.indexOf(char).coerceAtLeast(0)
            }
        }
        (total + digit).toString()
    }.replace('号', '日')
}
