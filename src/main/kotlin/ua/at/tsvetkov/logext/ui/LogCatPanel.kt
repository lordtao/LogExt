package ua.at.tsvetkov.logext.ui

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VfsUtil
import ua.at.tsvetkov.logext.models.TagInfo
import ua.at.tsvetkov.logext.services.LogCatGlobalSettingsService
import ua.at.tsvetkov.logext.services.LogCatListenerService
import ua.at.tsvetkov.logext.services.LogCatSettingsService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JPanel
import javax.swing.Timer

class LogCatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val consoleView: ConsoleView = object : ConsoleViewImpl(project, true) {
        override fun createCompositeFilter(): com.intellij.execution.filters.CompositeFilter {
            val compositeFilter = com.intellij.execution.filters.CompositeFilter(project)
            compositeFilter.addFilter(LogSourceLinkFilter(project))
            return compositeFilter
        }
    }.apply {
        val console = this as ConsoleViewImpl
        console.clearMessageFilters()
        
        ApplicationManager.getApplication().invokeLater {
            val editor = console.editor
            editor?.contentComponent?.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (e.isPopupTrigger) showPopupMenu(e)
                }
                override fun mouseReleased(e: java.awt.event.MouseEvent) {
                    if (e.isPopupTrigger) showPopupMenu(e)
                }
                private fun showPopupMenu(e: java.awt.event.MouseEvent) {
                    val group = DefaultActionGroup()
                    addCustomContextActions(group)
                    
                    group.addSeparator()
                    val actionManager = ActionManager.getInstance()
                    val standardGroup = actionManager.getAction(ActionPlaces.EDITOR_POPUP) as? ActionGroup
                    if (standardGroup != null) {
                        group.addAll(standardGroup.getChildren(null).toList())
                    }

                    val popupMenu = actionManager.createActionPopupMenu("LogCatPopup", group)
                    popupMenu.component.show(e.component, e.x, e.y)
                }
            })
        }
    }

    private val allTags = mutableMapOf<String, TagInfo>()
    private val settings = LogCatSettingsService.getInstance(project)
    private val globalSettings = LogCatGlobalSettingsService.getInstance()
    private val rawLogsHistory = mutableListOf<String>()
    private val pidToProcess = mutableMapOf<String, String>()

    private val listenerService = project.service<LogCatListenerService>()

    private var filterHeader: LogFilterHeader? = null
    private var currentDevice: String? = null
    
    private var lastMetadata: String? = null
    private var autoScrollToEnd = true

    private val logBuffer = ConcurrentLinkedQueue<LogItem>()
    private val bufferTimer: Timer

    data class LogItem(
        val message: String,
        val levelChar: String,
        val tagName: String?,
        val isAppTag: Boolean
    )

    init {
        val header = LogFilterHeader(
            onDeviceChanged = { device ->
                currentDevice = device
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

        // Инициализация иконки фильтра при старте
        updateTagFilterIndicator()

        bufferTimer = Timer(100) {
            flushBuffer()
        }.apply { start() }

        val initialDevices = listenerService.getConnectedDevices()
        if (initialDevices.isNotEmpty()) {
            val deviceToStart = initialDevices[0]
            header.updateDevices(initialDevices)
            restartListening(deviceToStart)

            val adbProcesses = listenerService.getProcessList(deviceToStart)
            if (adbProcesses.isNotEmpty()) {
                adbProcesses.forEach { (pid, pkg) -> pidToProcess[pid] = pkg }
                val savedProcess = settings.getState().lastSelectedProcess
                header.updateProcesses(pidToProcess.values.distinct().sorted(), savedProcess)
            }
        }

        Timer(5000) {
            val devices = listenerService.getConnectedDevices()
            header.updateDevices(devices)
            
            val activeDevice = header.getSelectedDevice()
            if (activeDevice != null && activeDevice != "Loading devices...") {
                val adbProcesses = listenerService.getProcessList(activeDevice)
                var processesChanged = false
                val selectedPackage = header.getSelectedProcess()
                var currentSelectedPidChanged = false
                
                if (adbProcesses.isNotEmpty()) {
                    val currentPids = adbProcesses.keys
                    val pidsToRemove = pidToProcess.keys.filter { it !in currentPids }
                    if (pidsToRemove.isNotEmpty()) {
                        pidsToRemove.forEach { pid ->
                            if (pidToProcess[pid] == selectedPackage) {
                                currentSelectedPidChanged = true
                            }
                            pidToProcess.remove(pid)
                        }
                        processesChanged = true
                    }

                    adbProcesses.forEach { (pid, pkg) ->
                        if (pidToProcess[pid] != pkg) {
                            pidToProcess[pid] = pkg
                            processesChanged = true
                            if (pkg == selectedPackage) {
                                currentSelectedPidChanged = true
                            }
                        }
                    }
                }

                if (processesChanged || header.getSelectedProcess() == "All Processes" || header.getSelectedProcess() == null) {
                    val savedProcess = settings.getState().lastSelectedProcess
                    header.updateProcesses(pidToProcess.values.distinct().sorted(), savedProcess)
                    
                    if (currentSelectedPidChanged && selectedPackage != "All Processes") {
                        reFilterHistory()
                    }
                    
                    // Обновляем индикатор фильтра, так как могли появиться новые теги
                    updateTagFilterIndicator()
                }
            }
        }.start()

        Timer(3000) {
            val devices = listenerService.getConnectedDevices()
            if (devices.isNotEmpty()) {
                val activeDevice = header.getSelectedDevice() ?: devices[0]
                currentDevice = activeDevice
                restartListening(activeDevice)
                
                val adbProcesses = listenerService.getProcessList(activeDevice)
                if (adbProcesses.isNotEmpty()) {
                    adbProcesses.forEach { (pid, pkg) -> pidToProcess[pid] = pkg }
                    val savedProcess = settings.getState().lastSelectedProcess
                    header.updateProcesses(pidToProcess.values.distinct().sorted(), savedProcess)
                }
                updateTagFilterIndicator()
            }
        }.apply { isRepeats = false }.start()
    }

    private fun addCustomContextActions(group: DefaultActionGroup) {
        val editor = (consoleView as? ConsoleViewImpl)?.editor ?: return
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText
        val currentLineText = getLineAtCaret(editor) ?: return
        
        val tagName = extractTagFromFormattedLine(currentLineText)

        if (selectedText != null) {
            group.add(object : AnAction("Copy Selection", "Copy selected text to clipboard", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) {
                    CopyPasteManager.getInstance().setContents(StringSelection(selectedText))
                }
            })
        }

        group.add(object : AnAction("Copy Message", "Copy only the message part", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val msg = extractMessageOnly(currentLineText)
                CopyPasteManager.getInstance().setContents(StringSelection(msg))
            }
        })

        val searchText = selectedText ?: extractMessageOnly(currentLineText)
        group.add(object : AnAction("Search with Google", "Search selection or message in Google", AllIcons.Actions.Search) {
            override fun actionPerformed(e: AnActionEvent) {
                BrowserUtil.browse("https://www.google.com/search?q=" + URLEncoder.encode(searchText, "UTF-8"))
            }
        })

        group.add(object : AnAction("Explain with AI", "Send text to AI for explanation", AllIcons.Actions.IntentionBulb) {
            override fun actionPerformed(e: AnActionEvent) {
                val prompt = globalSettings.state.aiPrompt
                val fullQuery = "$prompt\n\n$searchText"
                
                if (!tryOpenInInternalAi(fullQuery)) {
                    BrowserUtil.browse("https://www.google.com/search?q=" + URLEncoder.encode(fullQuery, "UTF-8"))
                }
            }
        })

        if (tagName != null) {
            group.addSeparator()
            group.add(object : AnAction("Filter Tag '$tagName'", "Hide this tag in current project", AllIcons.General.Filter) {
                override fun actionPerformed(e: AnActionEvent) {
                    settings.setTagSelected(tagName, false, allTags.keys)
                    allTags[tagName]?.isSelected = false
                    updateTagFilterIndicator()
                    reFilterHistory()
                }
            })

            group.add(object : AnAction("Always Ignore Tag '$tagName'", "Add this tag to global blacklist", AllIcons.Actions.DeleteTag) {
                override fun actionPerformed(e: AnActionEvent) {
                    globalSettings.setTagIgnored(tagName, true)
                    allTags[tagName]?.isSelected = false
                    updateTagFilterIndicator()
                    reFilterHistory()
                }
            })
        }

        group.addSeparator()
        group.add(object : AnAction("Clear Log", "Clear console and history", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                clearAllLogs()
            }
        })
    }

    private fun tryOpenInInternalAi(query: String): Boolean {
        return try {
            val apiClass = Class.forName("com.android.tools.idea.gemini.GeminiPluginApi")
            val companionField = apiClass.getDeclaredField("Companion")
            val companion = companionField.get(null)
            val getInstanceMethod = companion.javaClass.getMethod("getInstance")
            val api = getInstanceMethod.invoke(companion)
            
            val requestSourceClass = Class.forName("com.android.tools.idea.gemini.GeminiPluginApi\$RequestSource")
            val logcatSource = requestSourceClass.getField("LOGCAT").get(null)
            
            val stageChatQueryMethod = api.javaClass.getMethod("stageChatQuery", Project::class.java, String::class.java, requestSourceClass)
            stageChatQueryMethod.invoke(api, project, query, logcatSource)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun extractMessageOnly(line: String): String {
        val levelMatch = Regex("""\s([VDIWEA])\s""").find(line) ?: return line
        val afterLevel = line.substring(levelMatch.range.last + 1).trim()
        
        val firstSpace = afterLevel.indexOf(' ')
        return if (firstSpace != -1) afterLevel.substring(firstSpace).trim() else afterLevel
    }

    private fun getLineAtCaret(editor: Editor): String? {
        val caretModel = editor.caretModel
        val document = editor.document
        val lineIndex = caretModel.logicalPosition.line
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null
        
        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        return document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
    }

    private fun extractTagFromFormattedLine(line: String): String? {
        val levelMatch = Regex("""\s([VDIWEA])\s""").find(line) ?: return null
        val levelIndex = levelMatch.range.last + 1
        
        val afterLevel = line.substring(levelIndex).trim()
        val tagCandidate = afterLevel.split(' ').firstOrNull()?.removeSuffix(":")
        return tagCandidate?.trim()
    }

    private fun updateTagFilterIndicator() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val initialTags = getFilteredTagsForCurrentProcess()
            val isFilterActive = initialTags.any { tag -> 
                !tag.isSelected && tag.isPresentInCurrentLog && !globalSettings.isTagIgnored(tag.name) 
            }
            ApplicationManager.getApplication().invokeLater {
                filterHeader?.setTagFilterActive(isFilterActive)
            }
        }
    }

    private fun flushBuffer() {
        if (logBuffer.isEmpty()) return
        
        ApplicationManager.getApplication().invokeLater {
            val editor = (consoleView as? ConsoleViewImpl)?.editor
            val scrollingModel = editor?.scrollingModel
            val oldOffset = scrollingModel?.verticalScrollOffset ?: 0

            var item = logBuffer.poll()
            while (item != null) {
                if (item.tagName != null) {
                    processParsedMessage(item.message, item.tagName, item.levelChar, item.isAppTag)
                } else {
                    consoleView.print(item.message + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                }
                item = logBuffer.poll()
            }
            
            if (autoScrollToEnd) {
                (consoleView as ConsoleViewImpl).scrollToEnd()
            } else if (scrollingModel != null) {
                scrollingModel.scrollVertically(oldOffset)
            }
        }
    }

    private fun showTagFilterDialog() {
        val tagsToShow = getFilteredTagsForCurrentProcess()
        val dialog = TagFilterDialog(project, tagsToShow)
        if (dialog.showAndGet()) {
            val newSelectedTags = tagsToShow.filter { it.isSelected }.map { it.name }.toSet()
            settings.setSelectedTags(newSelectedTags)
            updateTagFilterIndicator()
            reFilterHistory()
        }
    }

    private fun restartListening(deviceName: String?) {
        clearAllLogs()
        listenerService.startListening(deviceName, ::onMessageReceived)
    }

    private fun clearAllLogs() {
        synchronized(rawLogsHistory) {
            rawLogsHistory.clear()
            allTags.clear()
            pidToProcess.clear()
            lastMetadata = null
            logBuffer.clear()
            ApplicationManager.getApplication().invokeLater {
                consoleView.clear()
            }
        }
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
            val isTargetProcess = pkg == settings.getState().lastSelectedProcess
            val isNewPid = pidToProcess[detectedPid] != pkg

            if (isTargetProcess && isNewPid) {
                ApplicationManager.getApplication().invokeLater {
                    if (globalSettings.state.clearLogOnStart) {
                        clearAllLogs()
                    }
                    pidToProcess[detectedPid] = pkg
                    header.updateProcesses(pidToProcess.values.distinct().sorted(), pkg)

                    if (globalSettings.state.openOnStart) {
                        Timer(1000) {
                            ApplicationManager.getApplication().invokeLater {
                                val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                                    .getToolWindow("TAO LogExt")
                                if (toolWindow != null && !toolWindow.isVisible) {
                                    toolWindow.show()
                                } else {
                                    toolWindow?.activate(null)
                                }
                            }
                        }.apply { isRepeats = false }.start()
                    }
                }
            } else if (isNewPid) {
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

        if (tagName != null && globalSettings.isTagIgnored(tagName)) return

        synchronized(rawLogsHistory) {
            rawLogsHistory.add(line)
            if (rawLogsHistory.size > MAX_HISTORY_SIZE) {
                rawLogsHistory.removeAt(0)
            }
        }

        if (matchResult != null && tagName != null && levelChar != null) {
            if (header.isLevelSelected(levelChar)) {
                val isTagFromApp = isAppLine || (pid != null && tid != null && pid == tid)
                val messagePart = matchResult.groupValues.getOrNull(8) ?: ""
                val currentMetadata = "$date $time.$millis $pid $tid $levelChar $tagName"
                val formattedLine = formatLineBySettings(date, time, millis, pid, tid, levelChar, tagName, messagePart, currentMetadata)
                
                logBuffer.add(LogItem(formattedLine, levelChar, tagName, isTagFromApp))
                lastMetadata = currentMetadata
            }
        } else {
            val selectedTags = settings.getState().selectedTags
            if (selectedTags == null) {
                logBuffer.add(LogItem(line, "V", null, false))
            }
        }
    }

    private fun formatLineBySettings(
        date: String?, time: String?, millis: String?, pid: String?, tid: String?,
        levelChar: String?, tagName: String?, messagePart: String, currentMetadata: String
    ): String {
        val state = globalSettings.state
        
        if (!state.showDuplicateTags && lastMetadata == currentMetadata) {
            val sb = StringBuilder()
            
            if (state.showDate && date != null) sb.append(" ".repeat(date.length + 1))
            if (state.showTime && time != null) {
                sb.append(" ".repeat(time.length))
                if (state.showMillis && millis != null) sb.append(" ".repeat(millis.length + 1))
                sb.append(" ")
            }
            if (state.showPid && pid != null) sb.append(" ".repeat(6))
            if (state.showTid && tid != null) sb.append(" ".repeat(6))
            
            if (levelChar != null) sb.append("  ")
            
            if (tagName != null) {
                val width = state.tagWidth
                val tagLen = if (width > 0) width else tagName.length
                sb.append(" ".repeat(tagLen + 1))
            }
            
            sb.append(messagePart)
            return sb.toString()
        }

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
            sb.append(formattedTag).append(" ")
        }
        
        sb.append(messagePart)
        
        return sb.toString()
    }

    private fun processParsedMessage(line: String, tagName: String, levelChar: String, isAppTag: Boolean = false) {
        if (globalSettings.isTagIgnored(tagName)) return

        var isNewTag = false
        val tagInfo = allTags.getOrPut(tagName) {
            isNewTag = true
            TagInfo(tagName, isSelected = settings.isTagSelected(tagName), isApplicationTag = isAppTag)
        }
        tagInfo.isPresentInCurrentLog = true
        if (isAppTag) tagInfo.isApplicationTag = true

        if (isNewTag) {
            updateTagFilterIndicator()
        }

        val selectedTags = settings.getState().selectedTags
        if (selectedTags == null || selectedTags.contains(tagName)) {
            printToConsole(line, levelChar)
        }
    }

    private fun printToConsole(formattedMessage: String, levelChar: String) {
        val attrs = globalSettings.getLevelAttributes(levelChar)
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
            lastMetadata = null
            logBuffer.clear()
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
                    
                    if (globalSettings.isTagIgnored(tagName)) return@forEach

                    val selectedProcess = header.getSelectedProcess()
                    val targetPid = if (selectedProcess != null && selectedProcess != "All Processes") {
                        pidToProcess.entries.find { it.value == selectedProcess }?.key
                    } else null

                    if (targetPid != null && pid != targetPid) return@forEach
                    
                    if (header.isLevelSelected(levelChar)) {
                        val isTagFromApp = (targetPid != null && pid == targetPid) || (pid == tid)
                        val messagePart = matchResult.groupValues.getOrNull(8) ?: ""
                        val currentMetadata = "$date $time.$millis $pid $tid $levelChar $tagName"
                        
                        val formattedLine = formatLineBySettings(date, time, millis, pid, tid, levelChar, tagName, messagePart, currentMetadata)
                        processParsedMessage(formattedLine, tagName, levelChar, isTagFromApp)
                        
                        lastMetadata = currentMetadata
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

        val clearAllAction = object : AnAction("Clear All", "Clear console and history", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                clearAllLogs()
            }
        }
        actionGroup.add(clearAllAction)
        actionGroup.addSeparator()

        actionGroup.add(object : ToggleAction("Scroll to the End", "Scroll to the end of log", AllIcons.RunConfigurations.Scroll_down) {
            override fun isSelected(e: AnActionEvent): Boolean = autoScrollToEnd
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                autoScrollToEnd = state
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
            com.intellij.openapi.util.io.FileUtil.writeToFile(file, text)
        } catch (_: Exception) {

        }
    }

    override fun dispose() {
        bufferTimer.stop()
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 50000
    }
}
