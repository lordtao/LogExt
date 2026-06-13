package ua.at.tsvetkov.logext.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ua.at.tsvetkov.logext.models.TagInfo
import ua.at.tsvetkov.logext.services.LogCatGlobalSettingsService
import ua.at.tsvetkov.logext.services.LogCatSettingsService
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent

class TagFilterDialog(
    project: Project,
    private val allTags: List<TagInfo>
) : DialogWrapper(project) {

    private val settings = LogCatSettingsService.getInstance(project)
    private val globalSettings = LogCatGlobalSettingsService.getInstance()
    private val workingTags = allTags.toMutableList()
    private val ignoredTagsSet = globalSettings.state.ignoredTags.toMutableSet()

    private val centerPanel = JPanel(GridLayout(1, 2, 10, 0))
    private val searchArea = JBTextArea(2, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.customLine(JBColor.border(), 1)
    }

    private val tagComponents = mutableMapOf<String, JComponent>()
    private val ignoredComponents = mutableMapOf<String, JComponent>()
    
    private var tagsScrollPane: JBScrollPane? = null
    private var ignoredScrollPane: JBScrollPane? = null
    
    private var showOnlyInactive = false

    init {
        title = "Filter Tags"

        val state = settings.getState()
        searchArea.text = state.lastTagSearch

        init()
        updatePanels()
        setupSearch()
    }

    override fun createNorthPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(5, 0))

        val label = JBLabel("Quick search: ")
        mainPanel.add(label, BorderLayout.WEST)

        val centerContainer = JPanel()
        centerContainer.layout = BoxLayout(centerContainer, BoxLayout.Y_AXIS)

        val searchInputPanel = JPanel(BorderLayout(2, 0))
        val scrollPane = JBScrollPane(searchArea).apply {
            border = null
            minimumSize = Dimension(0, 40)
        }
        searchInputPanel.add(scrollPane, BorderLayout.CENTER)

        val actionGroup = DefaultActionGroup()
        val toolbar = ActionManager.getInstance().createActionToolbar("TagFilterSearch", actionGroup, true)
        
        actionGroup.add(object : AnAction("Clear Search", "Clear search query", AllIcons.Actions.Close) {
            override fun actionPerformed(e: AnActionEvent) {
                searchArea.text = ""
            }
        })
        
        actionGroup.add(object : ToggleAction("Match Case", "Match case", AllIcons.Actions.MatchCase) {
            override fun isSelected(e: AnActionEvent): Boolean = settings.getState().lastTagMatchCase
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                settings.getState().lastTagMatchCase = state
                applyFilter()
                toolbar.updateActionsImmediately()
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        toolbar.targetComponent = searchArea
        searchInputPanel.add(toolbar.component, BorderLayout.EAST)

        centerContainer.add(searchInputPanel)

        val hintLabel = JBLabel("Enter multiple tags separated by spaces or new lines to search for any match.").apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
        }
        centerContainer.add(Box.createVerticalStrut(2))
        centerContainer.add(hintLabel)

        mainPanel.add(centerContainer, BorderLayout.CENTER)
        mainPanel.border = JBUI.Borders.emptyBottom(10)
        return mainPanel
    }

    override fun createCenterPanel(): JComponent {
        centerPanel.preferredSize = Dimension(900, 600)
        return centerPanel
    }

    private fun setupSearch() {
        searchArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilter()
            }
        })
    }

    private fun applyFilter() {
        val query = searchArea.text
        val matchCase = settings.getState().lastTagMatchCase
        val searchTerms = query.split(Regex("[\\s\\n\\r]+")).filter { it.isNotEmpty() }

        for ((tagName, component) in tagComponents) {
            val tagInfo = workingTags.find { it.name == tagName }
            val matchesQuery = if (searchTerms.isEmpty()) true else {
                searchTerms.any { term ->
                    if (matchCase) tagName.contains(term) else tagName.lowercase().contains(term.lowercase())
                }
            }
            val matchesInactiveFilter = if (showOnlyInactive) tagInfo?.isSelected == false else true
            component.isVisible = matchesQuery && matchesInactiveFilter
        }

        for ((tagName, component) in ignoredComponents) {
            val matchesQuery = if (searchTerms.isEmpty()) true else {
                searchTerms.any { term ->
                    if (matchCase) tagName.contains(term) else tagName.lowercase().contains(term.lowercase())
                }
            }
            component.isVisible = matchesQuery
        }

        centerPanel.revalidate()
        centerPanel.repaint()
    }

    private fun updatePanels() {
        val tagsScrollValue = tagsScrollPane?.verticalScrollBar?.value ?: 0
        val ignoredScrollValue = ignoredScrollPane?.verticalScrollBar?.value ?: 0

        centerPanel.removeAll()
        tagComponents.clear()
        ignoredComponents.clear()

        val activeTags = workingTags.filter { !ignoredTagsSet.contains(it.name) }
            .sortedWith(compareByDescending<TagInfo> { it.isApplicationTag }.thenBy { it.name })

        val ignoredTagsNames = ignoredTagsSet.toList().sorted()

        val tagsPanel = createTagGroupPanel("Tags", activeTags, true)
        val ignoredPanel = createIgnoredGroupPanel("Ignored Tags", ignoredTagsNames)
        
        centerPanel.add(tagsPanel)
        centerPanel.add(ignoredPanel)

        SwingUtilities.invokeLater {
            tagsScrollPane?.verticalScrollBar?.value = tagsScrollValue
            ignoredScrollPane?.verticalScrollBar?.value = ignoredScrollValue
        }

        applyFilter()
    }

    private fun createTagGroupPanel(
        title: String,
        groupTags: List<TagInfo>,
        showIgnoreButton: Boolean
    ): JPanel {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyBottom(5)
        }, BorderLayout.NORTH)

        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)

        val checkBoxes = mutableListOf<JBCheckBox>()

        groupTags.forEach { tag ->
            val row = JPanel(BorderLayout())
            row.maximumSize = Dimension(Int.MAX_VALUE, 32)
            row.border = JBUI.Borders.empty(1, 0)

            val cb = JBCheckBox(tag.name, tag.isSelected).apply {
                if (tag.isApplicationTag) font = font.deriveFont(Font.BOLD)
            }

            if (!tag.isPresentInCurrentLog) cb.foreground = JBColor.RED
            cb.addActionListener { tag.isSelected = cb.isSelected }
            checkBoxes.add(cb)
            row.add(cb, BorderLayout.CENTER)

            if (showIgnoreButton) {
                val ignoreBtn = JButton(AllIcons.Actions.Forward).apply {
                    toolTipText = "Move to Ignored"
                    preferredSize = Dimension(28, 24)
                    margin = JBUI.emptyInsets()
                    isFocusable = false
                }
                ignoreBtn.addActionListener {
                    ignoredTagsSet.add(tag.name)
                    updatePanels()
                }
                row.add(ignoreBtn, BorderLayout.EAST)
            }

            tagComponents[tag.name] = row
            listPanel.add(row)
        }

        val scroll = JBScrollPane(listPanel)
        tagsScrollPane = scroll
        panel.add(scroll, BorderLayout.CENTER)

        val footer = JPanel()
        footer.layout = BoxLayout(footer, BoxLayout.Y_AXIS)

        val mainButtons = JPanel(GridLayout(1, 2))
        val selectAll = JButton("Select All")
        selectAll.addActionListener {
            checkBoxes.forEach {
                if (it.parent.isVisible) {
                    it.isSelected = true
                    workingTags.find { t -> t.name == it.text }?.isSelected = true
                }
            }
        }
        val clearAll = JButton("Clear All")
        clearAll.addActionListener {
            checkBoxes.forEach {
                if (it.parent.isVisible) {
                    it.isSelected = false
                    workingTags.find { t -> t.name == it.text }?.isSelected = false
                }
            }
        }
        mainButtons.add(selectAll)
        mainButtons.add(clearAll)
        footer.add(mainButtons)

        val bottomButtonsPanel = JPanel(BorderLayout())
        val showInactiveBtn = JToggleButton("Show inactive")
        showInactiveBtn.isSelected = showOnlyInactive
        if (showOnlyInactive) showInactiveBtn.text = "Show all"
        showInactiveBtn.addActionListener {
            showOnlyInactive = showInactiveBtn.isSelected
            showInactiveBtn.text = if (showOnlyInactive) "Show all" else "Show inactive"
            applyFilter()
        }
        bottomButtonsPanel.add(showInactiveBtn, BorderLayout.WEST)

        val ignoreAllBtn = JButton("Ignore all")
        ignoreAllBtn.addActionListener {
            groupTags.forEach {
                val comp = tagComponents[it.name]
                if (comp != null && comp.isVisible) ignoredTagsSet.add(it.name)
            }
            updatePanels()
        }
        bottomButtonsPanel.add(ignoreAllBtn, BorderLayout.EAST)
        footer.add(bottomButtonsPanel)

        panel.add(footer, BorderLayout.SOUTH)
        return panel
    }

    private fun createIgnoredGroupPanel(title: String, groupTags: List<String>): JPanel {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyBottom(5)
        }, BorderLayout.NORTH)

        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)

        groupTags.forEach { tagName ->
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            row.maximumSize = Dimension(Int.MAX_VALUE, 32)
            row.border = JBUI.Borders.empty(1, 0)

            val restoreBtn = JButton(AllIcons.Actions.Back).apply {
                toolTipText = "Restore from Ignored"
                preferredSize = Dimension(28, 24)
                margin = JBUI.emptyInsets()
                isFocusable = false
            }
            restoreBtn.addActionListener {
                ignoredTagsSet.remove(tagName)
                updatePanels()
            }
            row.add(restoreBtn)

            val label = JBLabel(tagName).apply {
                border = JBUI.Borders.emptyLeft(5)
            }
            row.add(label)

            ignoredComponents[tagName] = row
            listPanel.add(row)
        }

        val scroll = JBScrollPane(listPanel)
        ignoredScrollPane = scroll
        panel.add(scroll, BorderLayout.CENTER)

        val returnAllBtn = JButton("Return all")
        returnAllBtn.addActionListener {
            ignoredTagsSet.clear()
            updatePanels()
        }
        panel.add(returnAllBtn, BorderLayout.SOUTH)

        return panel
    }

    override fun doOKAction() {
        globalSettings.state.ignoredTags = ignoredTagsSet
        val state = settings.getState()
        state.lastTagSearch = searchArea.text
        super.doOKAction()
    }
}
