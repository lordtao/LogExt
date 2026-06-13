package ua.at.tsvetkov.logext.ui.logic

/**
 * Класс для парсинга строк LogCat и детекции процессов.
 */
class LogParser {

    private val threadtimeRegex = Regex("""(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):\s?(.*)""")
    private val processBoostRegex = Regex("perfColdLaunchBoost: (.*?), (\\d+)")

    data class ParsedLine(
        val date: String,
        val time: String,
        val millis: String,
        val pid: String,
        val tid: String,
        val level: String,
        val tag: String,
        val message: String,
        val metadata: String,
    )

    data class ProcessInfo(
        val packageName: String,
        val pid: String,
    )

    fun parse(line: String): ParsedLine? {
        val matchResult = threadtimeRegex.find(line) ?: return null
        val groups = matchResult.groupValues
        
        val date = groups[1]
        val time = groups[2]
        val millis = groups[3]
        val pid = groups[4]
        val tid = groups[5]
        val level = groups[6]
        val tag = groups[7].trim()
        val message = groups.getOrNull(8) ?: ""
        
        return ParsedLine(
            date, time, millis, pid, tid, level, tag, message,
            "$date $time.$millis $pid $tid $level $tag"
        )
    }

    fun detectProcess(line: String): ProcessInfo? {
        val match = processBoostRegex.find(line) ?: return null
        return ProcessInfo(match.groupValues[1].trim(), match.groupValues[2].trim())
    }
}
