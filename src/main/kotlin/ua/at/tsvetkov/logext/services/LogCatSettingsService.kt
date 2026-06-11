package ua.at.tsvetkov.logext.services

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.*
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import java.awt.Color

/**
 * Сервис для сохранения настроек плагина и состояния фильтров.
 */
@Service(Service.Level.PROJECT)
@State(name = "LogCatSettings", storages = [Storage("logext_plugin_settings.xml")])
class LogCatSettingsService : PersistentStateComponent<LogCatSettingsService.State> {

    class State {
        var selectedTags: Set<String>? = null
        var ignoredTags: MutableSet<String> = mutableSetOf()
        var lastSelectedProcess: String? = null
        var levelColors: MutableMap<String, LevelAttributes> = mutableMapOf()
        var clearLogOnStart: Boolean = true
        
        var showDate: Boolean = true
        var showTime: Boolean = true
        var showMillis: Boolean = true
        var showPid: Boolean = true
        var showTid: Boolean = true
        var tagWidth: Int = 23
        var lastExportPath: String? = null
        var minimizeForAi: Boolean = false
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

    fun isTagSelected(tag: String): Boolean {
        if (isTagIgnored(tag)) return false
        return myState.selectedTags?.contains(tag) ?: true
    }

    fun setTagSelected(tag: String, selected: Boolean) {
        val currentTags = myState.selectedTags?.toMutableSet() ?: mutableSetOf()
        if (selected) currentTags.add(tag) else currentTags.remove(tag)
        myState.selectedTags = currentTags
    }
    
    fun setSelectedTags(tags: Set<String>) {
        myState.selectedTags = tags
    }

    fun isTagIgnored(tag: String): Boolean = myState.ignoredTags.contains(tag)

    fun setTagIgnored(tag: String, ignored: Boolean) {
        if (ignored) {
            myState.ignoredTags.add(tag)
            setTagSelected(tag, false)
        } else {
            myState.ignoredTags.remove(tag)
        }
    }

    companion object {
        fun getInstance(project: Project): LogCatSettingsService = project.getService(LogCatSettingsService::class.java)
    }
}
