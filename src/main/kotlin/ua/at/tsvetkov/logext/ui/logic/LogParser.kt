package ua.at.tsvetkov.logext.ui.logic

import com.google.gson.Gson
import ua.at.tsvetkov.logext.models.LogExtFile
import ua.at.tsvetkov.logext.models.LogMessage
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Класс для парсинга строк LogCat и детекции процессов.
 */
class LogParser {

    private val threadtimeRegex = Regex("""(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):\s?(.*)""")
    private val processBoostRegex = Regex("perfColdLaunchBoost: (.*?), (\\d+)")
    private val gson = Gson()
    private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

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

    fun parseJsonFile(file: File): List<String> {
        return try {
            val json = file.readText()
            val logCatFile = gson.fromJson(json, LogExtFile::class.java)
            logCatFile.logcatMessages.map { msg -> convertToThreadTime(msg) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun convertToThreadTime(msg: LogMessage): String {
        val h = msg.header
        val dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(h.timestamp.seconds, h.timestamp.nanos.toLong()), ZoneId.systemDefault())
        val date = dt.format(dateFormatter)
        val time = dt.format(timeFormatter)
        val millis = (h.timestamp.nanos / 1_000_000).toString().padStart(3, '0')
        val level = h.logLevel.take(1)
        
        return "$date $time.$millis ${h.pid.toString().padStart(5)} ${h.tid.toString().padStart(5)} $level ${h.tag}: ${msg.message}"
    }

    fun detectProcess(line: String): ProcessInfo? {
        val match = processBoostRegex.find(line) ?: return null
        return ProcessInfo(match.groupValues[1].trim(), match.groupValues[2].trim())
    }
}
