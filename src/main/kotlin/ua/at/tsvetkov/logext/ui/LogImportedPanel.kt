package ua.at.tsvetkov.logext.ui

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import ua.at.tsvetkov.logext.models.TagInfo
import ua.at.tsvetkov.logext.services.LogExtGlobalSettingsService
import ua.at.tsvetkov.logext.ui.logic.LogExporter
import ua.at.tsvetkov.logext.ui.logic.LogFormatter
import ua.at.tsvetkov.logext.ui.logic.LogParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URLEncoder
import javax.swing.JPanel

/**
 * Панель для отображения импортированных логов из файла .logcat (JSON).
 */
class LogImportedPanel(private val project: Project, private val logFile: File) : JPanel(BorderLayout()), Disposable {

    private val consoleView: ConsoleView = object : ConsoleViewImpl(project, true) {
        override fun createCompositeFilter(): com.intellij.execution.filters.CompositeFilter {
            val compositeFilter = com.intellij.execution.filters.CompositeFilter(project)
            compositeFilter.addFilter(LogSourceLinkFilter(project))
            return compositeFilter
        }
    }.apply {
        val console = this as ConsoleViewImpl
        console.clearMessageFilters()
        ApplicationManager.getApplication().invokeLater { setupContextMenu(console.editor) }
    }

    private val allTags = mutableMapOf<String, TagInfo>()
    private val localSelectedTags = mutableSetOf<String>()
    private val globalSettings = LogExtGlobalSettingsService.getInstance()
    private val rawLogsHistory = mutableListOf<String>()
    
    private var isAllSelected = true
    private var fileTagsCount = 0

    private val parser = LogParser()
    private val formatter = LogFormatter()
    private val exporter = LogExporter(parser)

    private var filterHeader: LogFilterHeader? = null
    private var lastMetadata: String? = null

    init {
        val header = LogFilterHeader(
            onDeviceChanged = {},
            onProcessChanged = {},
            onLevelsChanged = { reFilterHistory() },
            onTagFilterClicked = { showTagFilterDialog() }
        ).apply {
            hideDeviceAndProcessSelectors()
        }
        filterHeader = header

        add(header, BorderLayout.NORTH)
        add(consoleView.component, BorderLayout.CENTER)
        createToolbar()
        Disposer.register(this, consoleView)

        loadLogFile()
    }

    private fun loadLogFile() {
        if (!logFile.exists()) return
        val lines = parser.parseJsonFile(logFile)
        
        val uniqueTags = mutableSetOf<String>()
        lines.forEach { line ->
            parser.parse(line)?.let { uniqueTags.add(it.tag) }
        }
        
        fileTagsCount = uniqueTags.size
        localSelectedTags.addAll(uniqueTags)
        isAllSelected = true
        
        synchronized(rawLogsHistory) {
            rawLogsHistory.addAll(lines)
        }
        reFilterHistory()
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
                    val children = standardGroup.getChildren(null)
                    group.addAll(*children)
                }
                val popupMenu = ActionManager.getInstance().createActionPopupMenu("ImportedLogPopup", group)
                popupMenu.component.show(e.component, e.x, e.y)
            }
        })
    }

    private fun addCustomContextActions(group: DefaultActionGroup) {
        val editor = (consoleView as? ConsoleViewImpl)?.editor ?: return
        val selectedText = editor.selectionModel.selectedText
        val currentLineText = getLineAtCaret(editor) ?: return
        val parsed = parser.parse(currentLineText)
        val tagName = parsed?.tag

        if (selectedText != null) {
            group.add(object : AnAction("Copy Selection", null, AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) { CopyPasteManager.getInstance().setContents(StringSelection(selectedText)) }
            })
        }

        group.add(object : AnAction("Copy Message", null, AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val msg = parsed?.message ?: currentLineText
                CopyPasteManager.getInstance().setContents(StringSelection(msg.trim()))
            }
        })

        val searchText = selectedText ?: parsed?.message ?: currentLineText
        group.add(object : AnAction("Search with Google", null, AllIcons.Actions.Search) {
            override fun actionPerformed(e: AnActionEvent) { BrowserUtil.browse("https://www.google.com/search?q=" + URLEncoder.encode(searchText.trim(), "UTF-8")) }
        })

        group.add(object : AnAction("Explain with AI", null, AllIcons.Actions.IntentionBulb) {
            override fun actionPerformed(e: AnActionEvent) {
                val query = "${globalSettings.state.aiPrompt}\n\n${searchText.trim()}"
                if (!tryOpenInInternalAi(query)) BrowserUtil.browse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
            }
        })

        if (tagName != null) {
            group.addSeparator()
            group.add(object : AnAction("Filter Tag '$tagName'", null, AllIcons.General.Filter) {
                override fun actionPerformed(e: AnActionEvent) {
                    localSelectedTags.remove(tagName)
                    isAllSelected = localSelectedTags.size == fileTagsCount
                    reFilterHistory()
                }
            })
        }
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

    private fun showTagFilterDialog() {
        val tagsToShow = getFilteredTags()
        if (TagFilterDialog(project, tagsToShow).showAndGet()) {
            localSelectedTags.clear()
            localSelectedTags.addAll(tagsToShow.asSequence().filter { it.isSelected }.map { it.name })
            isAllSelected = localSelectedTags.size == fileTagsCount
            reFilterHistory()
        }
    }

    private fun reFilterHistory() {
        val header = filterHeader ?: return
        ApplicationManager.getApplication().invokeLater {
            consoleView.clear()
            lastMetadata = null
            val historyCopy = synchronized(rawLogsHistory) { rawLogsHistory.toList() }
            historyCopy.forEach { message ->
                val parsed = parser.parse(message)
                if (parsed != null) {
                    if (globalSettings.isTagIgnored(parsed.tag)) return@forEach
                    if (header.isLevelSelected(parsed.level)) {
                        if (localSelectedTags.contains(parsed.tag)) {
                            val formattedLine = formatter.format(parsed, globalSettings.state, lastMetadata == parsed.metadata)
                            processParsedMessage(formattedLine, parsed.tag, parsed.level)
                            lastMetadata = parsed.metadata
                        }
                    }
                } else {
                    if (isAllSelected) consoleView.print(message + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                }
            }
        }
    }

    private fun processParsedMessage(formattedLine: String, tagName: String, levelChar: String) {
        val attrs = globalSettings.getLevelAttributes(levelChar)
        val textAttributes = TextAttributes().apply {
            attrs.foregroundColor?.let { foregroundColor = Color.decode(it) }
            attrs.backgroundColor?.let { backgroundColor = Color.decode(it) }
        }
        consoleView.print(formattedLine + "\n", ConsoleViewContentType("LogExt_$levelChar", textAttributes))
    }

    private fun getFilteredTags(): List<TagInfo> {
        val currentTagsInFile = mutableSetOf<String>()
        synchronized(rawLogsHistory) {
            rawLogsHistory.forEach { line ->
                parser.parse(line)?.let { currentTagsInFile.add(it.tag) }
            }
        }
        
        return currentTagsInFile.map { tagName ->
            TagInfo(tagName, isSelected = localSelectedTags.contains(tagName), isApplicationTag = false).apply {
                isPresentInCurrentLog = true
            }
        }.sortedBy { it.name }
    }

    private fun createToolbar() {
        val actionGroup = DefaultActionGroup()
        
        val consoleActions = consoleView.createConsoleActions()
        val scrollToEndAction = consoleActions.find { it.templatePresentation.icon == AllIcons.RunConfigurations.Scroll_down }
        if (scrollToEndAction != null) actionGroup.add(scrollToEndAction)

        actionGroup.add(object : ToggleAction("Soft-Wrap", null, AllIcons.Actions.ToggleSoftWrap) {
            override fun isSelected(e: AnActionEvent): Boolean = (consoleView as ConsoleViewImpl).editor?.settings?.isUseSoftWraps ?: false
            override fun setSelected(e: AnActionEvent, state: Boolean) { (consoleView as ConsoleViewImpl).editor?.settings?.isUseSoftWraps = state }
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

        val toolbar = ActionManager.getInstance().createActionToolbar("ImportedLogToolbar", actionGroup, false)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.WEST)
    }

    override fun dispose() {}
}
