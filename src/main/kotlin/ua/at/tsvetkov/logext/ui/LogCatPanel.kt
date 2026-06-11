package ua.at.tsvetkov.logext.ui

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import ua.at.tsvetkov.logext.models.TagInfo
import ua.at.tsvetkov.logext.services.LogCatListenerService
import ua.at.tsvetkov.logext.services.LogCatSettingsService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Основная панель отображения логов.
 */
class LogCatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val consoleView: ConsoleView = object : ConsoleViewImpl(project, true) {
        override fun createCompositeFilter(): com.intellij.execution.filters.CompositeFilter {
            val compositeFilter = com.intellij.execution.filters.CompositeFilter(project)
            compositeFilter.addFilter(LogSourceLinkFilter(project))
            return compositeFilter
        }
    }.apply {
        (this as ConsoleViewImpl).clearMessageFilters()
    }

    private val allTags = mutableMapOf<String, TagInfo>()
    private val settings = LogCatSettingsService.getInstance(project)
    private val rawLogsHistory = mutableListOf<String>()
    private val pidToProcess = mutableMapOf<String, String>()

    private val listenerService = project.service<LogCatListenerService>()

    private var filterHeader: LogFilterHeader? = null

    init {
        val header = LogFilterHeader(
            onDeviceChanged = { device ->
                restartListening(device)
            },
            onProcessChanged = { process ->
                settings.getState().lastSelectedProcess = process
                reFilterHistory()
            },
            onLevelsChanged = {
                reFilterHistory()
            },
            onTagFilterClicked = {
                showTagFilterDialog()
            }
        )
        filterHeader = header

        add(header, BorderLayout.NORTH)
        add(consoleView.component, BorderLayout.CENTER)
        createToolbar()
        Disposer.register(this, consoleView)

        val initialDevices = listenerService.getConnectedDevices()
        if (initialDevices.isNotEmpty()) {
            header.updateDevices(initialDevices)
            restartListening(initialDevices[0])
        }

        Timer(5000) {
            val devices = listenerService.getConnectedDevices()
            header.updateDevices(devices)
            
            if (header.getSelectedProcess() == "All Processes" || header.getSelectedProcess() == null) {
                val savedProcess = settings.getState().lastSelectedProcess
                if (savedProcess != null && pidToProcess.values.contains(savedProcess)) {
                    header.updateProcesses(pidToProcess.values.distinct().sorted(), savedProcess)
                }
            }
        }.start()
    }

    private fun showTagFilterDialog() {
        val tagsToShow = getFilteredTagsForCurrentProcess()
        val dialog = TagFilterDialog(project, tagsToShow)
        if (dialog.showAndGet()) {
            val newSelectedTags = tagsToShow.filter { it.isSelected }.map { it.name }.toSet()
            settings.setSelectedTags(newSelectedTags)
            reFilterHistory()
        }
    }

    private fun restartListening(deviceName: String?) {
        synchronized(rawLogsHistory) {
            rawLogsHistory.clear()
            allTags.clear()
            pidToProcess.clear()
            ApplicationManager.getApplication().invokeLater {
                consoleView.clear()
            }
        }
        listenerService.startListening(deviceName, ::onMessageReceived)
    }

    private fun onMessageReceived(message: String) {
        val header = filterHeader ?: return

        message.split(Regex("\\r?\\n")).forEach { line ->
            if (line.isBlank()) return@forEach
            processSingleLine(line, header)
        }
    }

    private fun processSingleLine(line: String, header: LogFilterHeader) {
        val threadtimeRegex = Regex("""(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):\s?(.*)""")
        val matchResult = threadtimeRegex.find(line)

        val date = matchResult?.groupValues?.get(1)
        val time = matchResult?.groupValues?.get(2)
        val millis = matchResult?.groupValues?.get(3)
        val pid = matchResult?.groupValues?.get(4)
        val tid = matchResult?.groupValues?.get(5)
        val levelChar = matchResult?.groupValues?.get(6)
        val tagName = matchResult?.groupValues?.get(7)?.trim()

        val isProcessInfoLine = line.contains("perfColdLaunchBoost")
        val processMatch = Regex("perfColdLaunchBoost: (.*?), (\\d+)").find(line)
        processMatch?.let {
            val pkg = it.groupValues[1].trim()
            val detectedPid = it.groupValues[2].trim()
            
            if (settings.getState().clearLogOnStart && pkg == settings.getState().lastSelectedProcess) {
                if (pidToProcess[detectedPid] != pkg) {
                    ApplicationManager.getApplication().invokeLater {
                        synchronized(rawLogsHistory) {
                            rawLogsHistory.clear()
                            allTags.clear()
                            pidToProcess.clear()
                            consoleView.clear()
                        }
                        pidToProcess[detectedPid] = pkg
                        header.updateProcesses(pidToProcess.values.distinct().sorted(), pkg)
                    }
                }
            } else if (pidToProcess[detectedPid] != pkg) {
                pidToProcess[detectedPid] = pkg
                ApplicationManager.getApplication().invokeLater {
                    val preferred = settings.getState().lastSelectedProcess ?: pkg
                    header.updateProcesses(pidToProcess.values.distinct().sorted(), preferred)
                }
            }
        }

        val selectedProcess = header.getSelectedProcess()
        var isAppLine = false
        if (selectedProcess != null && selectedProcess != "All Processes") {
            val targetPid = pidToProcess.entries.find { it.value == selectedProcess }?.key
            if (pid != null && pid == targetPid) {
                isAppLine = true
            }
            if (!isProcessInfoLine && !isAppLine) return
        }

        if (tagName != null && settings.isTagIgnored(tagName)) return

        synchronized(rawLogsHistory) {
            rawLogsHistory.add(line)
            if (rawLogsHistory.size > MAX_HISTORY_SIZE) {
                rawLogsHistory.removeAt(0)
            }
        }

        ApplicationManager.getApplication().invokeLater {
            if (matchResult != null && tagName != null && levelChar != null) {
                if (header.isLevelSelected(levelChar)) {
                    val isTagFromApp = isAppLine || (pid != null && tid != null && pid == tid)
                    
                    val messagePart = matchResult.groupValues.getOrNull(8) ?: ""
                    val formattedLine = formatLineBySettings(date, time, millis, pid, tid, levelChar, tagName, messagePart)
                    processParsedMessage(formattedLine, tagName, levelChar, isTagFromApp)
                }
            } else {
                val selectedTags = settings.getState().selectedTags
                if (selectedTags == null) {
                    consoleView.print(line + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                }
            }
        }
    }

    private fun formatLineBySettings(
        date: String?, time: String?, millis: String?, pid: String?, tid: String?,
        levelChar: String?, tagName: String?, messagePart: String
    ): String {
        val state = settings.getState()
        val sb = StringBuilder()
        
        if (state.showDate && date != null) sb.append(date).append(" ")
        if (state.showTime && time != null) {
            sb.append(time)
            if (state.showMillis && millis != null) sb.append(".").append(millis)
            sb.append(" ")
        }
        if (state.showPid && pid != null) sb.append(pid.padStart(5)).append(" ")
        if (state.showTid && tid != null) sb.append(tid.padStart(5)).append(" ")
        
        if (levelChar != null) sb.append(levelChar).append(" ")
        
        if (tagName != null) {
            val width = state.tagWidth
            val formattedTag = if (width > 0) {
                if (tagName.length > width) tagName.substring(0, width)
                else tagName.padEnd(width)
            } else tagName
            sb.append(formattedTag).append(": ")
        }
        
        sb.append(messagePart)
        
        return sb.toString()
    }

    private fun processParsedMessage(line: String, tagName: String, levelChar: String, isAppTag: Boolean = false) {
        if (settings.isTagIgnored(tagName)) return

        val tagInfo = allTags.getOrPut(tagName) {
            TagInfo(tagName, isSelected = settings.isTagSelected(tagName), isApplicationTag = isAppTag)
        }
        tagInfo.isPresentInCurrentLog = true
        if (isAppTag) tagInfo.isApplicationTag = true

        val selectedTags = settings.getState().selectedTags
        if (selectedTags == null || selectedTags.contains(tagName)) {
            printToConsole(line, levelChar)
        }
    }

    private fun printToConsole(formattedMessage: String, levelChar: String) {
        val attrs = settings.getLevelAttributes(levelChar)
        val textAttributes = TextAttributes().apply {
            attrs.foregroundColor?.let { foregroundColor = Color.decode(it) }
            attrs.backgroundColor?.let { backgroundColor = Color.decode(it) }
        }
        
        val contentType = ConsoleViewContentType("LogCat_$levelChar", textAttributes)
        consoleView.print(formattedMessage + "\n", contentType)
    }

    private fun reFilterHistory() {
        val header = filterHeader ?: return
        ApplicationManager.getApplication().invokeLater {
            consoleView.clear()
            val historyCopy = synchronized(rawLogsHistory) { rawLogsHistory.toList() }
            historyCopy.forEach { message ->
                val threadtimeRegex = Regex("""(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):\s?(.*)""")
                val matchResult = threadtimeRegex.find(message)
                if (matchResult != null) {
                    val date = matchResult.groupValues[1]
                    val time = matchResult.groupValues[2]
                    val millis = matchResult.groupValues[3]
                    val pid = matchResult.groupValues[4]
                    val tid = matchResult.groupValues[5]
                    val levelChar = matchResult.groupValues[6]
                    val tagName = matchResult.groupValues[7].trim()
                    
                    if (settings.isTagIgnored(tagName)) return@forEach

                    val selectedProcess = header.getSelectedProcess()
                    val targetPid = if (selectedProcess != null && selectedProcess != "All Processes") {
                        pidToProcess.entries.find { it.value == selectedProcess }?.key
                    } else null

                    if (targetPid != null && pid != targetPid) return@forEach
                    
                    if (header.isLevelSelected(levelChar)) {
                        val isTagFromApp = (targetPid != null && pid == targetPid) || (pid == tid)
                        val messagePart = matchResult.groupValues.getOrNull(8) ?: ""
                        val formattedLine = formatLineBySettings(date, time, millis, pid, tid, levelChar, tagName, messagePart)
                        processParsedMessage(formattedLine, tagName, levelChar, isTagFromApp)
                    }
                } else {
                    val selectedProcess = header.getSelectedProcess()
                    if (selectedProcess == null || selectedProcess == "All Processes") {
                        val selectedTags = settings.getState().selectedTags
                        if (selectedTags == null) {
                            consoleView.print(message + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }
                    }
                }
            }
        }
    }

    private fun getFilteredTagsForCurrentProcess(): List<TagInfo> {
        val header = filterHeader ?: return emptyList()
        val selectedProcess = header.getSelectedProcess()

        val currentProcessTags = mutableSetOf<String>()
        val historyCopy = synchronized(rawLogsHistory) { rawLogsHistory.toList() }
        
        val targetPid = if (selectedProcess != null && selectedProcess != "All Processes") {
            pidToProcess.entries.find { it.value == selectedProcess }?.key
        } else null

        historyCopy.forEach { message ->
            val threadtimeRegex = Regex("""(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):\s?(.*)""")
            val matchResult = threadtimeRegex.find(message)
            if (matchResult != null) {
                val pid = matchResult.groupValues[4]
                val tagName = matchResult.groupValues[7].trim()
                if (targetPid == null || pid == targetPid) {
                    currentProcessTags.add(tagName)
                }
            }
        }

        val filteredTags = allTags.values.filter { tag ->
            tag.name in currentProcessTags || settings.isTagSelected(tag.name)
        }.toMutableList()

        filteredTags.forEach { tag ->
            tag.isPresentInCurrentLog = tag.name in currentProcessTags
        }

        return filteredTags.sortedBy { it.name }
    }

    private fun createToolbar() {
        val actionGroup = DefaultActionGroup()

        actionGroup.add(object : ToggleAction("Scroll to the End", "Scroll to the end of log", AllIcons.RunConfigurations.Scroll_down) {
            override fun isSelected(e: AnActionEvent): Boolean = (consoleView as ConsoleViewImpl).editor?.let {
                it.scrollingModel.verticalScrollOffset >= it.contentComponent.height - it.scrollingModel.visibleArea.height
            } ?: true

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state) {
                    (consoleView as ConsoleViewImpl).scrollToEnd()
                }
            }
            
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        actionGroup.add(object : ToggleAction("Soft-Wrap", "Toggle soft-wrap mode", AllIcons.Actions.ToggleSoftWrap) {
            override fun isSelected(e: AnActionEvent): Boolean = (consoleView as ConsoleViewImpl).editor?.settings?.isUseSoftWraps ?: false

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                (consoleView as ConsoleViewImpl).editor?.settings?.isUseSoftWraps = state
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        actionGroup.addSeparator()

        actionGroup.add(object : AnAction("Copy Log", "Copy current log", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val text = (consoleView as ConsoleViewImpl).text
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        })

        actionGroup.add(object : AnAction("Save Log", "Save log to file", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                val dialog = LogExportDialog(project)
                if (dialog.showAndGet()) {
                    val path = dialog.getExportPath()
                    if (path.isNotEmpty()) {
                        saveLogToFile(File(path), dialog.isMinimizeForAi())
                    }
                }
            }
        })

        actionGroup.addSeparator()

        actionGroup.add(object : AnAction("Settings", "Display settings", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                if (LogSettingsDialog(project).showAndGet()) {
                    reFilterHistory()
                }
            }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("LogCatToolbar", actionGroup, false)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.WEST)
    }

    private fun saveLogToFile(file: File, minimize: Boolean) {
        try {
            var text = (consoleView as ConsoleViewImpl).text
            if (minimize) {
                text = text.replace(Regex(" +"), " ")
            }
            FileUtil.writeToFile(file, text)
        } catch (_: Exception) {

        }
    }

    override fun dispose() {
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 50000
    }
}
