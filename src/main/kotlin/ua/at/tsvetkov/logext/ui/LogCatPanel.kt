package ua.at.tsvetkov.logext.ui

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
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
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Основная панель отображения логов.
 */
class LogCatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val consoleView: ConsoleView = ConsoleViewImpl(project, true)
    private val allTags = mutableMapOf<String, TagInfo>()
    private val settings = LogCatSettingsService.getInstance(project)
    private val rawLogsHistory = mutableListOf<String>()
    private val MAX_HISTORY_SIZE = 50000
    private val pidToProcess = mutableMapOf<String, String>()

    private val listenerService = project.service<LogCatListenerService>()

    private var filterHeader: LogFilterHeader? = null

    init {
        val header = LogFilterHeader(
            onDeviceChanged = { device ->
                restartListening(device)
            },
            onProcessChanged = { _ ->
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

        val processMatch = Regex("perfColdLaunchBoost: (.*?), (\\d+)").find(line)
        processMatch?.let {
            val pkg = it.groupValues[1].trim()
            val detectedPid = it.groupValues[2].trim()
            if (pidToProcess[detectedPid] != pkg) {
                pidToProcess[detectedPid] = pkg
                ApplicationManager.getApplication().invokeLater {
                    header.updateProcesses(pidToProcess.values.distinct().sorted(), pkg)
                }
            }
        }

        val selectedProcess = header.getSelectedProcess()
        if (selectedProcess != null && selectedProcess != "All Processes") {
            val targetPid = pidToProcess.entries.find { it.value == selectedProcess }?.key
            if (pid == null || pid != targetPid) return
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
                consoleView.print(line + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
            }
        }
    }

    private fun processParsedMessage(line: String, tagName: String, levelChar: String) {
        val tagInfo = allTags.getOrPut(tagName) {
            TagInfo(tagName, isSelected = settings.isTagSelected(tagName))
        }
        tagInfo.isPresentInCurrentLog = true

        val selectedTags = settings.getState().selectedTags
        if (selectedTags.isEmpty() || tagInfo.isSelected) {
            val coloredMessage = formatParsedMessage(line, levelChar)
            printToConsole(coloredMessage)
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

    private fun printToConsole(formattedMessage: String) {
        val contentType = when {
            formattedMessage.contains("[ERROR]") || formattedMessage.contains("[ASSERT]") ->
                ConsoleViewContentType.ERROR_OUTPUT
            formattedMessage.contains("[WARN]") ->
                ConsoleViewContentType.LOG_WARNING_OUTPUT
            formattedMessage.contains("[INFO]") ->
                ConsoleViewContentType.LOG_INFO_OUTPUT
            formattedMessage.contains("[DEBUG]") || formattedMessage.contains("[VERBOSE]") ->
                ConsoleViewContentType.LOG_DEBUG_OUTPUT
            else -> ConsoleViewContentType.NORMAL_OUTPUT
        }
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
                    if (header.isLevelSelected(levelChar)) {
                        processParsedMessage(message, matchResult.groupValues[4].trim(), levelChar)
                    }
                } else {
                    consoleView.print(message + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                }
            }
        }
    }

    private fun createToolbar() {
        val actionGroup = DefaultActionGroup()

        actionGroup.add(object : AnAction("Filter Tags", "Filter by tags", AllIcons.General.Filter) {
            override fun actionPerformed(e: AnActionEvent) {
                val dialog = TagFilterDialog(project, allTags.values.toList())
                if (dialog.showAndGet()) {
                    allTags.values.forEach { tag ->
                        settings.setTagSelected(tag.name, tag.isSelected)
                    }
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
}
