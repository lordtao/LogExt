package ua.at.tsvetkov.logext.services

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Color

/**
 * Глобальный сервис для хранения настроек плагина, общих для всех проектов.
 */
@Service(Service.Level.APP)
@State(name = "LogCatGlobalSettings", storages = [Storage("logext_global_settings.xml")])
class LogCatGlobalSettingsService : PersistentStateComponent<LogCatGlobalSettingsService.State> {

    class State {
        var ignoredTags: MutableSet<String> = mutableSetOf()
        var levelColors: MutableMap<String, LevelAttributes> = mutableMapOf()
        
        var showDate: Boolean = true
        var showTime: Boolean = true
        var showMillis: Boolean = true
        var showPid: Boolean = true
        var showTid: Boolean = true
        var tagWidth: Int = 23
        
        var clearLogOnStart: Boolean = true
        var openOnStart: Boolean = true
        
        var lastExportPath: String? = null
        var minimizeForAi: Boolean = false
        var showDuplicateTags: Boolean = false
        var aiPrompt: String = "Explain the following Android log entry and provide possible solutions. " +
                "Answer in Russian language."
    }

    data class LevelAttributes(
        var foregroundColor: String? = null,
        var backgroundColor: String? = null
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun getLevelAttributes(level: String): LevelAttributes {
        val userAttrs = myState.levelColors[level]
        if (userAttrs != null && (userAttrs.foregroundColor != null || userAttrs.backgroundColor != null)) {
            return userAttrs
        }
        
        val scheme = EditorColorsManager.getInstance().globalScheme
        val contentType = when (level) {
            "V" -> ConsoleViewContentType.SYSTEM_OUTPUT
            "D" -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
            "I" -> ConsoleViewContentType.LOG_INFO_OUTPUT
            "W" -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            "E" -> ConsoleViewContentType.LOG_ERROR_OUTPUT
            "A" -> ConsoleViewContentType.LOG_ERROR_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }

        val attrs = scheme.getAttributes(contentType.attributesKey)
        val fg = attrs?.foregroundColor?.let { colorToHex(it) }
        val bg = attrs?.backgroundColor?.let { colorToHex(it) }
        
        return LevelAttributes(fg, bg)
    }

    fun setLevelAttributes(level: String, foreground: Color?, background: Color?) {
        myState.levelColors[level] = LevelAttributes(
            foreground?.let { colorToHex(it) },
            background?.let { colorToHex(it) }
        )
    }

    private fun colorToHex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    fun isTagIgnored(tag: String): Boolean = myState.ignoredTags.contains(tag)

    fun setTagIgnored(tag: String, ignored: Boolean) {
        if (ignored) {
            myState.ignoredTags.add(tag)
        } else {
            myState.ignoredTags.remove(tag)
        }
    }

    companion object {
        fun getInstance(): LogCatGlobalSettingsService =
            ApplicationManager.getApplication().getService(LogCatGlobalSettingsService::class.java)
    }
}
