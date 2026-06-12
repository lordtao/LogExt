package ua.at.tsvetkov.logext.services

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Сервис для сохранения настроек конкретного проекта (фильтры, история поиска).
 */
@Service(Service.Level.PROJECT)
@State(name = "LogCatSettings", storages = [Storage("logext_plugin_settings.xml")])
class LogCatSettingsService : PersistentStateComponent<LogCatSettingsService.State> {

    class State {
        var selectedTags: Set<String>? = null
        var lastSelectedProcess: String? = null
        var lastTagSearch: String = ""
        var lastTagMatchCase: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun isTagSelected(tag: String): Boolean {
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

    companion object {
        fun getInstance(project: Project): LogCatSettingsService = project.getService(LogCatSettingsService::class.java)
    }
}
