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
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.content.ContentFactory
import ua.at.tsvetkov.logext.models.TagInfo
import ua.at.tsvetkov.logext.services.LogExtGlobalSettingsService
import ua.at.tsvetkov.logext.services.LogExtListenerService
import ua.at.tsvetkov.logext.services.LogExtSettingsService
import ua.at.tsvetkov.logext.ui.logic.LogExporter
import ua.at.tsvetkov.logext.ui.logic.LogFormatter
import ua.at.tsvetkov.logext.ui.logic.LogParser
import ua.at.tsvetkov.logext.ui.logic.ProcessManager
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Основная панель отображения логов.
 */
class LogExtPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

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
            if (editor != null) {
                setupContextMenu(editor)
                editor.settings.isUseSoftWraps = LogExtGlobalSettingsService.getInstance().state.isSoftWrap
            }
        }
    }

    private val allTags = mutableMapOf<String, TagInfo>()
    private val settings = LogExtSettingsService.getInstance(project)
    private val globalSettings = LogExtGlobalSettingsService.getInstance()
    private val rawLogsHistory = mutableListOf<String>()
    private val listenerService = project.service<LogExtListenerService>()

    private val parser = LogParser()
    private val formatter = LogFormatter()
    private val processManager = ProcessManager()
    private val exporter = LogExporter(parser)

    private var filterHeader: LogFilterHeader? = null
    private var currentDevice: String? = null
    private var lastMetadata: String? = null
    
    private var isPaused = false
    private val notificationPanel = EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
        text = "Logcat is paused"
        createActionLabel("Resume") { resumeLogs() }
        isVisible = false
    }

    private val logBuffer = ConcurrentLinkedQueue<LogItem>()
    private val bufferTimer: Timer

    data class LogItem(
        val message: String,
        val levelChar: String,
        val tagName: String?,
        val isAppTag: Boolean,
    )

    init {
        val header = LogFilterHeader(
            onDeviceChanged = { device ->
                currentDevice = device
                restartListening(device)
            },
            onProcessChanged = { process ->
                settings.state.lastSelectedProcess = process
                reFilterHistory()
            },
            onLevelsChanged = { reFilterHistory() },
            onTagFilterClicked = { showTagFilterDialog() },
        )
        filterHeader = header

        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(notificationPanel, BorderLayout.NORTH)
        centerPanel.add(consoleView.component, BorderLayout.CENTER)

        add(header, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        createToolbar()
        Disposer.register(this, consoleView)

        updateTagFilterIndicator()
        bufferTimer = Timer(100) { flushBuffer() }.apply { start() }

        initAdb()
        setupAdbTimers(header)
    }

    private fun resumeLogs() {
        isPaused = false
        notificationPanel.isVisible = false
        reFilterHistory()
    }

    private fun initAdb() {
        val initialDevices = listenerService.getConnectedDevices()
        if (initialDevices.isNotEmpty()) {
            val deviceToStart = initialDevices[0]
            filterHeader?.updateDevices(initialDevices)
            restartListening(deviceToStart)

            val adbProcesses = listenerService.getProcessList(deviceToStart)
            if (adbProcesses.isNotEmpty()) {
                adbProcesses.forEach { (pid, pkg) -> processManager.updateProcess(pid, pkg) }
                filterHeader?.updateProcesses(processManager.getAllPackages(), settings.state.lastSelectedProcess)
            }
        }
    }

    private fun setupAdbTimers(header: LogFilterHeader) {
        Timer(5000) {
            val devices = listenerService.getConnectedDevices()
            header.updateDevices(devices)
            
            val activeDevice = header.getSelectedDevice()
            if ((activeDevice != null) && (activeDevice != "Loading devices...")) {
                val adbProcesses = listenerService.getProcessList(activeDevice)
                var processesChanged = false
                val selectedPackage = header.getSelectedProcess()
                var currentSelectedPidChanged = false
                
                if (adbProcesses.isNotEmpty()) {
                    val currentPids = adbProcesses.keys
                    val pidsToRemove = processManager.getPids().filter { it !in currentPids }
                    pidsToRemove.forEach { pid ->
                        if (processManager.getPackageByPidChecked(pid, selectedPackage ?: "")) {
                            currentSelectedPidChanged = true
                        }
                        processManager.removeProcess(pid)
                        processesChanged = true
                    }

                    adbProcesses.forEach { (pid, pkg) ->
                        if (processManager.updateProcess(pid, pkg)) {
                            processesChanged = true
                            if (pkg == selectedPackage) currentSelectedPidChanged = true
                        }
                    }
                }

                if (processesChanged || (header.getSelectedProcess() == "All Processes") || (header.getSelectedProcess() == null)) {
                    header.updateProcesses(processManager.getAllPackages(), header.getSelectedProcess())
                    if (currentSelectedPidChanged && (selectedPackage != "All Processes")) reFilterHistory()
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
                    adbProcesses.forEach { (pid, pkg) -> processManager.updateProcess(pid, pkg) }
                    header.updateProcesses(processManager.getAllPackages(), header.getSelectedProcess())
                }
                updateTagFilterIndicator()
            }
        }.apply { isRepeats = false }.start()
    }

    private fun setupContextMenu(editor: Editor?) {
        editor?.contentComponent?.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) showPopupMenu(e) }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) showPopupMenu(e) }
            private fun showPopupMenu(e: java.awt.event.MouseEvent) {
                val group = DefaultActionGroup()
                addCustomContextActions(group)
                group.addSeparator()
                val standardGroup = ActionManager.getInstance().getAction(ActionPlaces.EDITOR_POPUP) as? ActionGroup
                if (standardGroup != null) {
                    group.addAll(standardGroup)
                }
                val popupMenu = ActionManager.getInstance().createActionPopupMenu("LogExtPopup", group)
                popupMenu.component.show(e.component, e.x, e.y)
            }
        })
    }

    private fun addCustomContextActions(group: DefaultActionGroup) {
        val editor = (consoleView as? ConsoleViewImpl)?.editor ?: return
        val selectedText = editor.selectionModel.selectedText
        val lineIndex = editor.caretModel.logicalPosition.line
        val currentLineText = getLineAtCaret(editor) ?: return
        
        val tagName = findTagContextually(editor, lineIndex)

        if (selectedText != null) {
            group.add(object : AnAction("Copy Selection", null, AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) { CopyPasteManager.getInstance().setContents(StringSelection(selectedText)) }
            })
        }

        group.add(object : AnAction("Copy Message", null, AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val msg = extractMessageOnly(currentLineText)
                CopyPasteManager.getInstance().setContents(StringSelection(msg.trim()))
            }
        })

        val searchText = selectedText ?: extractMessageOnly(currentLineText)
        group.add(object : AnAction("Search with Google", null, AllIcons.Actions.Search) {
            override fun actionPerformed(e: AnActionEvent) {
                val url = "https://www.google.com/search?q=" + URLEncoder.encode(searchText.trim(), "UTF-8")
                BrowserUtil.browse(url)
            }
        })

        group.add(object : AnAction("Explain with AI", null, AllIcons.Actions.IntentionBulb) {
            override fun actionPerformed(e: AnActionEvent) {
                val query = "${globalSettings.state.aiPrompt}\n\n${searchText.trim()}"
                if (!tryOpenInInternalAi(query)) {
                    val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
                    BrowserUtil.browse(url)
                }
            }
        })

        if (tagName != null) {
            group.addSeparator()
            group.add(object : AnAction("Filter Tag '$tagName'", null, AllIcons.General.Filter) {
                override fun actionPerformed(e: AnActionEvent) {
                    settings.setTagSelected(tagName, selected = false, allKnownTags = allTags.keys)
                    allTags[tagName]?.isSelected = false
                    updateTagFilterIndicator()
                    reFilterHistory()
                }
            })

            group.add(object : AnAction("Always Ignore Tag '$tagName'", null, AllIcons.Actions.DeleteTag) {
                override fun actionPerformed(e: AnActionEvent) {
                    globalSettings.setTagIgnored(tagName, ignored = true)
                    allTags[tagName]?.isSelected = false
                    updateTagFilterIndicator()
                    reFilterHistory()
                }
            })
            
            if (selectedText != null) {
                val shortText = if (selectedText.length > 20) selectedText.take(20).trim() + "..." else selectedText.trim()
                group.add(object : AnAction("Always Ignore String '$shortText'", null, AllIcons.Actions.DeleteTag) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val cleanText = selectedText.take(50).trim()
                        if (cleanText.isNotEmpty()) {
                            globalSettings.addIgnoredString(cleanText)
                            reFilterHistory()
                        }
                    }
                })
            }
        }

        group.addSeparator()
        group.add(object : AnAction("Clear Log", null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) { clearAllLogs() }
        })
    }

    private fun findTagContextually(editor: Editor, lineIndex: Int): String? {
        val document = editor.document
        var currentIndex = lineIndex
        
        while (currentIndex >= 0) {
            val start = document.getLineStartOffset(currentIndex)
            val end = document.getLineEndOffset(currentIndex)
            val text = document.getText(com.intellij.openapi.util.TextRange(start, end))
            
            val tag = extractTagFromFormattedLine(text)
            if (tag != null) return tag
            
            if (text.isNotEmpty() && !text.startsWith(" ")) break
            currentIndex--
        }
        return null
    }

    private fun extractTagFromFormattedLine(line: String): String? {
        val levelMatch = Regex("""\s([VDIWEA])\s""").find(line) ?: return null
        val afterLevel = line.substring(levelMatch.range.last + 1).trim()
        return afterLevel.split(' ').firstOrNull()
    }

    private fun extractMessageOnly(line: String): String {
        val levelMatch = Regex("""\s([VDIWEA])\s""").find(line) ?: return line
        val afterLevel = line.substring(levelMatch.range.last + 1).trim()
        val firstSpace = afterLevel.indexOf(' ')
        return if (firstSpace != -1) afterLevel.substring(firstSpace).trim() else afterLevel
    }

    private fun tryOpenInInternalAi(query: String): Boolean {
        return try {
            val apiClass = Class.forName("com.android.tools.idea.gemini.GeminiPluginApi")
            val companion = apiClass.getDeclaredField("Companion").get(null)
            val api = companion.javaClass.getMethod("getInstance").invoke(companion)
            val requestSourceClass = Class.forName("com.android.tools.idea.gemini.GeminiPluginApi\$RequestSource")
            val logcatSource = requestSourceClass.getField("LOGCAT").get(null)
            val stageChatQueryMethod = api.javaClass.getMethod("stageChatQuery", Project::class.java, String::class.java, requestSourceClass)
            stageChatQueryMethod.invoke(api, project, query, logcatSource)
            true
        } catch (_: Exception) { false }
    }

    private fun getLineAtCaret(editor: Editor): String? {
        val document = editor.document
        val lineIndex = editor.caretModel.logicalPosition.line
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null
        return document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex)))
    }

    private fun updateTagFilterIndicator() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val initialTags = getFilteredTagsForCurrentProcess()
            val isFilterActive = initialTags.any { !it.isSelected && it.isPresentInCurrentLog && !globalSettings.isTagIgnored(it.name) }
            ApplicationManager.getApplication().invokeLater { filterHeader?.setTagFilterActive(isFilterActive) }
        }
    }

    private fun flushBuffer() {
        if (logBuffer.isEmpty() || isPaused) return
        ApplicationManager.getApplication().invokeLater {
            val console = consoleView as? ConsoleViewImpl ?: return@invokeLater
            val editor = console.editor
            val scrollingModel = editor?.scrollingModel
            val isAtBottom = if (scrollingModel != null) {
                val verticalScrollOffset = scrollingModel.verticalScrollOffset
                val visibleHeight = scrollingModel.visibleArea.height
                val totalHeight = editor.contentComponent.height
                totalHeight - (verticalScrollOffset + visibleHeight) < 50
            } else true

            var item = logBuffer.poll()
            while (item != null) {
                if (item.tagName != null) processParsedMessage(item.message, item.tagName, item.levelChar, item.isAppTag)
                else console.print(item.message + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                item = logBuffer.poll()
            }
            
            if (globalSettings.state.isAutoScroll && isAtBottom) {
                console.scrollToEnd()
            }
        }
    }

    private fun showTagFilterDialog() {
        val tagsToShow = getFilteredTagsForCurrentProcess()
        val dialog = TagFilterDialog(project, tagsToShow)
        if (dialog.showAndGet()) {
            val updatedTags = dialog.getWorkingTags()
            
            updatedTags.forEach { updated ->
                val existing = allTags[updated.name]
                if (existing == null) {
                    allTags[updated.name] = updated
                } else {
                    existing.isSelected = updated.isSelected
                }
            }

            settings.setSelectedTags(updatedTags.asSequence().filter { it.isSelected }.map { it.name }.toSet())
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
            processManager.clear()
            lastMetadata = null
            logBuffer.clear()
            ApplicationManager.getApplication().invokeLater { consoleView.clear() }
        }
    }

    private fun onMessageReceived(message: String) {
        val header = filterHeader ?: return
        message.split(Regex("\\r?\\n")).forEach { line ->
            if (line.isNotBlank()) processSingleLine(line, header)
        }
    }

    private fun processSingleLine(line: String, header: LogFilterHeader) {
        val parsed = parser.parse(line)
        val processInfo = parser.detectProcess(line)

        processInfo?.let {
            val isTargetProcess = it.packageName == settings.state.lastSelectedProcess
            val isNewPid = processManager.updateProcess(it.pid, it.packageName)

            if (isTargetProcess && isNewPid) {
                ApplicationManager.getApplication().invokeLater {
                    if (globalSettings.state.clearLogOnStart) clearAllLogs()
                    header.updateProcesses(processManager.getAllPackages(), it.packageName)
                    if (globalSettings.state.openOnStart) {
                        Timer(1000) {
                            ApplicationManager.getApplication().invokeLater {
                                val tw = ToolWindowManager.getInstance(project).getToolWindow("TAO LogExt")
                                if (tw != null && !tw.isVisible) tw.show() else tw?.activate(null)
                            }
                        }.apply { isRepeats = false }.start()
                    }
                }
            } else if (isNewPid) {
                ApplicationManager.getApplication().invokeLater {
                    header.updateProcesses(processManager.getAllPackages(), header.getSelectedProcess())
                }
            }
        }

        val selectedProcess = header.getSelectedProcess()
        var isAppLine = false
        if (selectedProcess != null && (selectedProcess != "All Processes")) {
            if (parsed?.pid != null && processManager.getPackageByPidChecked(parsed.pid, expectedPackage = selectedProcess)) isAppLine = true
            if (processInfo == null && !isAppLine) return
        }

        if (parsed?.tag != null && globalSettings.isTagIgnored(parsed.tag)) return
        if (globalSettings.isStringIgnored(line)) return

        synchronized(rawLogsHistory) {
            rawLogsHistory.add(line)
            if (rawLogsHistory.size > globalSettings.state.maxHistorySize) rawLogsHistory.removeAt(0)
        }

        if (parsed != null) {
            if (header.isLevelSelected(parsed.level)) {
                val isTagFromApp = isAppLine || (parsed.pid == parsed.tid)
                val formattedLine = formatter.format(parsed, globalSettings.state, isDuplicate = (lastMetadata == parsed.metadata))
                logBuffer.add(LogItem(formattedLine, parsed.level, parsed.tag, isTagFromApp))
                lastMetadata = parsed.metadata
            }
        } else if (settings.state.selectedTags == null) {
            logBuffer.add(LogItem(line, "V", null, false))
        }
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
            settings.state.selectedTags?.let {
                val updatedTags = it.toMutableSet()
                updatedTags.add(tagName)
                settings.setSelectedTags(updatedTags)
            }
            updateTagFilterIndicator()
        }

        if (settings.state.selectedTags?.contains(tagName) != false) {
            val attrs = globalSettings.getLevelAttributes(levelChar)
            val textAttributes = TextAttributes().apply {
                attrs.foregroundColor?.let { foregroundColor = Color.decode(it) }
                attrs.backgroundColor?.let { backgroundColor = Color.decode(it) }
            }
            consoleView.print(line + "\n", ConsoleViewContentType("LogExt_$levelChar", textAttributes))
        }
    }

    private fun reFilterHistory() {
        val header = filterHeader ?: return
        ApplicationManager.getApplication().invokeLater {
            consoleView.clear()
            lastMetadata = null
            logBuffer.clear()
            val historyCopy = synchronized(rawLogsHistory) { rawLogsHistory.toList() }
            historyCopy.forEach { message ->
                val parsed = parser.parse(message)
                if (parsed != null) {
                    if (globalSettings.isTagIgnored(parsed.tag) || globalSettings.isStringIgnored(message)) return@forEach
                    val selectedProcess = header.getSelectedProcess()
                    val targetPid = if (selectedProcess != null && (selectedProcess != "All Processes")) processManager.findPidByPackage(selectedProcess) else null
                    if (targetPid != null && (parsed.pid != targetPid)) return@forEach
                    
                    if (header.isLevelSelected(parsed.level)) {
                        val isTagFromApp = (targetPid != null) || (parsed.pid == parsed.tid)
                        val formattedLine = formatter.format(parsed, globalSettings.state, isDuplicate = (lastMetadata == parsed.metadata))
                        processParsedMessage(formattedLine, parsed.tag, parsed.level, isTagFromApp)
                        lastMetadata = parsed.metadata
                    }
                } else if (header.getSelectedProcess() == null || (header.getSelectedProcess() == "All Processes")) {
                    if (settings.state.selectedTags == null) consoleView.print(message + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                }
            }
        }
    }

    private fun getFilteredTagsForCurrentProcess(): List<TagInfo> {
        val selectedProcess = filterHeader?.getSelectedProcess()
        val currentProcessTags = mutableSetOf<String>()
        val historyCopy = synchronized(rawLogsHistory) { rawLogsHistory.toList() }
        val targetPid = if (selectedProcess != null && (selectedProcess != "All Processes")) processManager.findPidByPackage(selectedProcess) else null

        historyCopy.forEach { message ->
            val parsed = parser.parse(message)
            if (parsed != null && ((targetPid == null) || (parsed.pid == targetPid))) currentProcessTags.add(parsed.tag)
        }

        val filteredTags = allTags.values.asSequence().filter { it.name in currentProcessTags || settings.isTagSelected(it.name) }.toMutableList()
        filteredTags.forEach { it.isPresentInCurrentLog = it.name in currentProcessTags }
        return filteredTags.sortedBy { it.name }
    }

    private fun createToolbar() {
        val actionGroup = DefaultActionGroup()
        
        actionGroup.add(object : ToggleAction("Pause/Resume", "Pause or resume Logcat stream", AllIcons.Actions.Pause) {
            override fun isSelected(e: AnActionEvent): Boolean = isPaused
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                isPaused = state
                notificationPanel.isVisible = isPaused
                if (!isPaused) resumeLogs()
            }
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.icon = if (isPaused) AllIcons.Actions.Resume else AllIcons.Actions.Pause
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        actionGroup.addSeparator()

        actionGroup.add(object : AnAction("Import Log", "Import LogCat from file", AllIcons.ToolbarDecorator.Import) {
            override fun actionPerformed(e: AnActionEvent) {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
                    title = "Import LogExt File"
                    description = "Choose a .logcat file to analyze"
                    withExtensionFilter("Logcat files", "logcat")
                }
                
                val virtualFile = FileChooser.chooseFile(descriptor, project, null)
                virtualFile?.let {
                    val logFile = VfsUtilCore.virtualToIoFile(it)
                    val toolWindowManager = ToolWindowManager.getInstance(project)
                    val toolWindow = toolWindowManager.getToolWindow("TAO LogExt") ?: return
                    val importedPanel = LogImportedPanel(project, logFile)
                    
                    val content = ContentFactory.getInstance().createContent(importedPanel, logFile.name, false)
                    content.isCloseable = true
                    
                    toolWindow.contentManager.addContent(content)
                    toolWindow.contentManager.setSelectedContent(content)
                    Disposer.register(content, importedPanel)
                }
            }
        })
        
        actionGroup.addSeparator()
        actionGroup.add(object : AnAction("Clear All", null, AllIcons.Actions.GC) { override fun actionPerformed(e: AnActionEvent) { clearAllLogs() } })
        actionGroup.addSeparator()

        actionGroup.add(object : ToggleAction("Scroll to the End", null, AllIcons.RunConfigurations.Scroll_down) {
            override fun isSelected(e: AnActionEvent): Boolean = globalSettings.state.isAutoScroll
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                globalSettings.state.isAutoScroll = state
                if (state) (consoleView as ConsoleViewImpl).scrollToEnd()
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        actionGroup.add(object : ToggleAction("Soft-Wrap", null, AllIcons.Actions.ToggleSoftWrap) {
            override fun isSelected(e: AnActionEvent): Boolean = globalSettings.state.isSoftWrap
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                globalSettings.state.isSoftWrap = state
                (consoleView as ConsoleViewImpl).editor?.settings?.isUseSoftWraps = state
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        actionGroup.addSeparator()
        actionGroup.add(object : AnAction("Copy Log", null, AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) { CopyPasteManager.getInstance().setContents(StringSelection((consoleView as ConsoleViewImpl).text)) }
        })

        actionGroup.add(object : AnAction("Save Log", null, AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                val dialog = LogExportDialog(project)
                if (dialog.showAndGet()) {
                    val path = dialog.getExportPath()
                    if (path.isNotEmpty()) exporter.export(File(path), (consoleView as ConsoleViewImpl).text, dialog.isMinimizeForAi())
                }
            }
        })

        actionGroup.addSeparator()
        actionGroup.add(object : AnAction("Settings", null, AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) { if (LogSettingsDialog(project).showAndGet()) reFilterHistory() }
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("LogCatToolbar", actionGroup, false)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.WEST)
    }

    override fun dispose() { bufferTimer.stop() }
}
