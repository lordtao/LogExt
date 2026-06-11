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
import com.intellij.openapi.vfs.VfsUtil
import ua.at.tsvetkov.logext.models.TagInfo
import ua.at.tsvetkov.logext.services.LogCatListenerService
import ua.at.tsvetkov.logext.services.LogCatSettingsService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Основная панель отображения логов.
 */
class LogCatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val consoleView: ConsoleView = object : ConsoleViewImpl(project, true) {
        override fun createCompositeFilter(): com.intellij.execution.filters.CompositeFilter {
            val compositeFilter = com.intellij.execution.filters.CompositeFilter(project)
            // Добавляем наш фильтр для создания ссылок на исходный код
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
            }
        )
        filterHeader = header

        add(header, BorderLayout.NORTH)
        add(consoleView.component, BorderLayout.CENTER)
        createToolbar()
        Disposer.register(this, consoleView)

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

        restartListening(null)
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
        val threadtimeRegex = Regex("""\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):""")
        val matchResult = threadtimeRegex.find(line)

        val pid = matchResult?.groupValues?.get(1)
        val levelChar = matchResult?.groupValues?.get(3)
        val tagName = matchResult?.groupValues?.get(4)?.trim()

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
        if (selectedProcess != null && selectedProcess != "All Processes") {
            val targetPid = pidToProcess.entries.find { it.value == selectedProcess }?.key
            if (!isProcessInfoLine) {
                if (pid == null || pid != targetPid) return
            }
        }

        synchronized(rawLogsHistory) {
            rawLogsHistory.add(line)
            if (rawLogsHistory.size > MAX_HISTORY_SIZE) {
                rawLogsHistory.removeAt(0)
            }
        }

        ApplicationManager.getApplication().invokeLater {
            if (matchResult != null && tagName != null && levelChar != null) {
                if (header.isLevelSelected(levelChar)) {
                    processParsedMessage(line, tagName, levelChar)
                }
            } else {
                // Строки без тега (системные) показываем только если фильтр тегов не ограничивает вывод
                val selectedTags = settings.getState().selectedTags
                if (selectedTags == null) {
                    consoleView.print(line + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                }
            }
        }
    }

    private fun processParsedMessage(line: String, tagName: String, levelChar: String) {
        val tagInfo = allTags.getOrPut(tagName) {
            TagInfo(tagName, isSelected = settings.isTagSelected(tagName))
        }
        tagInfo.isPresentInCurrentLog = true

        val selectedTags = settings.getState().selectedTags
        // Если selectedTags == null, значит выбраны ВСЕ теги (по умолчанию)
        if (selectedTags == null || selectedTags.contains(tagName)) {
            printToConsole(line, levelChar)
        }
    }

    private fun formatParsedMessage(line: String, levelChar: String): String {
        val fullLevel = when (levelChar) {
            "V" -> "[VERBOSE]"
            "D" -> "[DEBUG]"
            "I" -> "[INFO]"
            "W" -> "[WARN]"
            "E" -> "[ERROR]"
            "A" -> "[ASSERT]"
            else -> "[$levelChar]"
        }
        return line.replaceFirst(" $levelChar ", " $fullLevel ")
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
                val threadtimeRegex = Regex("""\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):""")
                val matchResult = threadtimeRegex.find(message)
                if (matchResult != null) {
                    val levelChar = matchResult.groupValues[3]
                    val pid = matchResult.groupValues[1]
                    
                    val selectedProcess = header.getSelectedProcess()
                    val targetPid = if (selectedProcess != null && selectedProcess != "All Processes") {
                        pidToProcess.entries.find { it.value == selectedProcess }?.key
                    } else null

                    if (targetPid != null && pid != targetPid) return@forEach
                    
                    if (header.isLevelSelected(levelChar)) {
                        processParsedMessage(message, matchResult.groupValues[4].trim(), levelChar)
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
        val header = filterHeader ?: return allTags.values.toList()
        val selectedProcess = header.getSelectedProcess()

        if (selectedProcess != null && selectedProcess != "All Processes") {
            val targetPid = pidToProcess.entries.find { it.value == selectedProcess }?.key
            if (targetPid != null) {
                val processTags = mutableSetOf<String>()
                val historyCopy = synchronized(rawLogsHistory) { rawLogsHistory.toList() }

                historyCopy.forEach { message ->
                    val threadtimeRegex = Regex("""\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+(.*?):""")
                    val matchResult = threadtimeRegex.find(message)
                    if (matchResult != null && matchResult.groupValues[1] == targetPid) {
                        processTags.add(matchResult.groupValues[4].trim())
                    }
                }

                return allTags.values.filter { it.name in processTags || settings.isTagSelected(it.name) }
            }
        }
        return allTags.values.toList()
    }

    private fun createToolbar() {
        val actionGroup = DefaultActionGroup()

        actionGroup.add(object : AnAction("Filter Tags", "Filter by tags", AllIcons.General.Filter) {
            override fun actionPerformed(e: AnActionEvent) {
                val tagsToShow = getFilteredTagsForCurrentProcess()
                val dialog = TagFilterDialog(project, tagsToShow)
                if (dialog.showAndGet()) {
                    val newSelectedTags = tagsToShow.filter { it.isSelected }.map { it.name }.toSet()
                    settings.setSelectedTags(newSelectedTags)
                    reFilterHistory()
                }
            }
        })

        actionGroup.add(object : AnAction("Copy Log", "Copy current log", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val text = (consoleView as ConsoleViewImpl).text
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        })

        actionGroup.add(object : AnAction("Save Log", "Save log to file", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                saveLogToFile()
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

    private fun saveLogToFile() {
        val descriptor = FileSaverDescriptor("Save Log", "Save LogCat output to file", "txt")
        val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val fileWrapper = saveDialog.save(VfsUtil.getUserHomeDir(), "logcat_output.txt")
        fileWrapper?.let {
            val text = (consoleView as ConsoleViewImpl).text
            it.file.writeBytes(text.toByteArray())
        }
    }

    override fun dispose() {
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 50000
    }
}
