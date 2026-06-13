package ua.at.tsvetkov.logext.ui.logic

import ua.at.tsvetkov.logext.services.LogCatGlobalSettingsService

/**
 * Класс для форматирования строк лога согласно настройкам отображения.
 */
class LogFormatter {

    fun format(
        parsed: LogParser.ParsedLine,
        state: LogCatGlobalSettingsService.State,
        isDuplicate: Boolean
    ): String {
        if (!state.showDuplicateTags && isDuplicate) {
            val sb = StringBuilder()
            if (state.showDate) sb.append(" ".repeat(parsed.date.length + 1))
            if (state.showTime) {
                sb.append(" ".repeat(parsed.time.length))
                if (state.showMillis) sb.append(" ".repeat(parsed.millis.length + 1))
                sb.append(" ")
            }
            if (state.showPid) sb.append(" ".repeat(6))
            if (state.showTid) sb.append(" ".repeat(6))
            sb.append("  ")
            
            val width = state.tagWidth
            val tagLen = if (width > 0) width else parsed.tag.length
            sb.append(" ".repeat(tagLen + 1))
            sb.append(parsed.message)
            return sb.toString()
        }

        val sb = StringBuilder()
        if (state.showDate) sb.append(parsed.date).append(" ")
        if (state.showTime) {
            sb.append(parsed.time)
            if (state.showMillis) sb.append(".").append(parsed.millis)
            sb.append(" ")
        }
        if (state.showPid) sb.append(parsed.pid.padStart(5)).append(" ")
        if (state.showTid) sb.append(parsed.tid.padStart(5)).append(" ")
        sb.append(parsed.level).append(" ")
        
        val width = state.tagWidth
        val formattedTag = if (width > 0) {
            if (parsed.tag.length > width) parsed.tag.substring(0, width)
            else parsed.tag.padEnd(width)
        } else parsed.tag
        
        sb.append(formattedTag).append(" ")
        sb.append(parsed.message)
        
        return sb.toString()
    }
}
