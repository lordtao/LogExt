package ua.at.tsvetkov.logext.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import ua.at.tsvetkov.logext.models.TagInfo
import ua.at.tsvetkov.logext.services.LogCatSettingsService
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * Диалоговое окно для фильтрации тегов с тремя группами: App, Other, Ignored.
 */
class TagFilterDialog(
    project: Project,
    private val allTags: List<TagInfo>
) : DialogWrapper(project) {

    private val settings = LogCatSettingsService.getInstance(project)
    private val workingTags = allTags.toMutableList()
    private val ignoredTagsSet = settings.getState().ignoredTags.toMutableSet()
    
    private val centerPanel = JPanel(GridLayout(1, 3, 10, 0))
    private val searchField = SearchTextField(true)
    
    private var isMatchCaseActive = false
    private val matchCaseBtn = JToggleButton(AllIcons.Actions.MatchCase).apply {
        toolTipText = "Match Case"
        preferredSize = Dimension(28, 24)
        isFocusable = false
    }
    
    private val tagComponents = mutableMapOf<String, JComponent>()

    init {
        title = "Filter Tags"
        init()
        updatePanels()
        setupSearch()
    }

    override fun createNorthPanel(): JComponent {
        val panel = JPanel(BorderLayout(5, 0))
        panel.add(JBLabel("Quick search: "), BorderLayout.WEST)
        
        val searchPanel = JPanel(BorderLayout(5, 0))
        searchPanel.add(searchField, BorderLayout.CENTER)
        searchPanel.add(matchCaseBtn, BorderLayout.EAST)
        
        panel.add(searchPanel, BorderLayout.CENTER)
        panel.border = JBUI.Borders.emptyBottom(10)
        return panel
    }

    override fun createCenterPanel(): JComponent {
        centerPanel.preferredSize = Dimension(1300, 600)
        return centerPanel
    }

    private fun setupSearch() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                applyFilter()
            }
        })
        matchCaseBtn.addActionListener {
            isMatchCaseActive = matchCaseBtn.isSelected
            applyFilter()
        }
    }

    private fun applyFilter() {
        val text = searchField.text
        
        for ((tagName, component) in tagComponents) {
            val visible = if (text.isEmpty()) {
                true
            } else {
                if (isMatchCaseActive) {
                    tagName.contains(text)
                } else {
                    tagName.lowercase().contains(text.lowercase())
                }
            }
            component.isVisible = visible
        }
        
        centerPanel.revalidate()
        centerPanel.repaint()
    }

    private fun updatePanels() {
        centerPanel.removeAll()
        tagComponents.clear()
        
        val appTags = workingTags.filter { it.isApplicationTag && !ignoredTagsSet.contains(it.name) }
            .sortedBy { it.name }
        val otherTags = workingTags.filter { !it.isApplicationTag && !ignoredTagsSet.contains(it.name) }
            .sortedBy { it.name }
        val ignoredTagsNames = ignoredTagsSet.toList().sorted()

        centerPanel.add(createTagGroupPanel("Process Tags", appTags, true, false))
        centerPanel.add(createTagGroupPanel("Other Tags", otherTags, true, true))
        centerPanel.add(createIgnoredGroupPanel("Ignored Tags", ignoredTagsNames))
        
        applyFilter()
    }

    private fun createTagGroupPanel(
        title: String, 
        groupTags: List<TagInfo>, 
        showIgnoreButton: Boolean,
        showIgnoreAll: Boolean
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
            
            val cb = JBCheckBox(tag.name, tag.isSelected)
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

        panel.add(JBScrollPane(listPanel), BorderLayout.CENTER)

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

        if (showIgnoreAll) {
            val ignoreAllBtn = JButton("Ignore all")
            ignoreAllBtn.addActionListener {
                groupTags.forEach { 
                    val comp = tagComponents[it.name]
                    if (comp != null && comp.isVisible) {
                        ignoredTagsSet.add(it.name)
                    }
                }
                updatePanels()
            }
            val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            btnRow.add(ignoreAllBtn)
            footer.add(btnRow)
        }

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
            val row = JPanel(BorderLayout())
            row.maximumSize = Dimension(Int.MAX_VALUE, 32)
            row.border = JBUI.Borders.empty(1, 0)
            
            row.add(JBLabel(tagName), BorderLayout.CENTER)

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
            row.add(restoreBtn, BorderLayout.EAST)
            
            tagComponents[tagName] = row
            listPanel.add(row)
        }

        panel.add(JBScrollPane(listPanel), BorderLayout.CENTER)

        val returnAllBtn = JButton("Return all")
        returnAllBtn.addActionListener {
            ignoredTagsSet.clear()
            updatePanels()
        }
        panel.add(returnAllBtn, BorderLayout.SOUTH)

        return panel
    }

    override fun doOKAction() {
        settings.getState().ignoredTags = ignoredTagsSet
        super.doOKAction()
    }
}
