package ua.at.tsvetkov.logext.services

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Сервис для сохранения настроек плагина и состояния фильтров.
 */
@Service(Service.Level.PROJECT)
@State(name = "LogCatSettings", storages = [Storage("logcat_plugin_settings.xml")])
class LogCatSettingsService : PersistentStateComponent<LogCatSettingsService.State> {

    class State {
        var selectedTags: Set<String> = mutableSetOf()
        var fontSize: Int = 12
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun isTagSelected(tag: String): Boolean = myState.selectedTags.contains(tag)

    fun setTagSelected(tag: String, selected: Boolean) {
        val newTags = myState.selectedTags.toMutableSet()
        if (selected) newTags.add(tag) else newTags.remove(tag)
        myState.selectedTags = newTags
    }

    companion object {
        fun getInstance(project: Project): LogCatSettingsService = project.getService(LogCatSettingsService::class.java)
    }
}
