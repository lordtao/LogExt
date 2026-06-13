package ua.at.tsvetkov.logext.ui.logic

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Класс для экспорта логов в файл с оптимизацией для ИИ.
 */
class LogExporter(private val parser: LogParser) {

    fun export(file: File, fullText: String, minimize: Boolean) {
        val lines = fullText.split("\n")
        val sb = StringBuilder()
        var prevMetadata: String? = null

        for (line in lines) {
            if (line.isBlank()) {
                sb.append("\n")
                continue
            }

            val parsed = parser.parse(line)
            if (parsed != null) {
                if (parsed.metadata == prevMetadata) {
                    val messageOnly = parsed.message.trim()
                    sb.append(if (minimize) messageOnly.replace(Regex(" +"), " ") else messageOnly).append("\n")
                } else {
                    sb.append(if (minimize) line.replace(Regex(" +"), " ") else line).append("\n")
                    prevMetadata = parsed.metadata
                }
            } else {
                sb.append(if (minimize) line.replace(Regex(" +"), " ") else line).append("\n")
                prevMetadata = null
            }
        }
        file.writeText(sb.toString(), StandardCharsets.UTF_8)
    }
}
