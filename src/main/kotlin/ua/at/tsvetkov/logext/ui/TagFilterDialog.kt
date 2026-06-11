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
    private val searchField = SearchTextField()
    
    private val tagComponents = mutableMapOf<String, JComponent>() // Имя тега -> Компонент строки

    init {
        title = "Filter Tags"
        init()
        updatePanels()
        setupSearch()
    }

    override fun createNorthPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel("Quick search: "), BorderLayout.WEST)
        panel.add(searchField, BorderLayout.CENTER)
        panel.border = JBUI.Borders.emptyBottom(10)
        return panel
    }

    override fun createCenterPanel(): JComponent {
        centerPanel.preferredSize = Dimension(1200, 600)
        return centerPanel
    }

    private fun setupSearch() {
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val text = searchField.text.lowercase()
                if (text.isEmpty()) return
                
                // Ищем первое совпадение в любой из колонок
                for ((tagName, component) in tagComponents) {
                    if (tagName.lowercase().contains(text)) {
                        scrollToComponent(component)
                        break
                    }
                }
            }
        })
    }

    private fun scrollToComponent(component: JComponent) {
        val parent = component.parent
        if (parent is JComponent) {
            val rect = component.bounds
            // Расширяем прямоугольник, чтобы он был виден в центре или сверху
            parent.scrollRectToVisible(rect)
        }
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
        
        centerPanel.revalidate()
        centerPanel.repaint()
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
            row.maximumSize = Dimension(Int.MAX_VALUE, 30)
            
            val cb = JBCheckBox(tag.name, tag.isSelected)
            if (!tag.isPresentInCurrentLog) cb.foreground = JBColor.RED
            cb.addActionListener { tag.isSelected = cb.isSelected }
            checkBoxes.add(cb)
            row.add(cb, BorderLayout.CENTER)

            if (showIgnoreButton) {
                val ignoreBtn = JButton(AllIcons.Actions.Forward).apply {
                    toolTipText = "Move to Ignored"
                    preferredSize = Dimension(24, 24)
                    isContentAreaFilled = false
                    isBorderPainted = false
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
            checkBoxes.forEach { it.isSelected = true }
            groupTags.forEach { it.isSelected = true }
        }
        val clearAll = JButton("Clear All")
        clearAll.addActionListener {
            checkBoxes.forEach { it.isSelected = false }
            groupTags.forEach { it.isSelected = false }
        }
        mainButtons.add(selectAll)
        mainButtons.add(clearAll)
        footer.add(mainButtons)

        if (showIgnoreAll) {
            val ignoreAllBtn = JButton("Ignore all")
            ignoreAllBtn.addActionListener {
                groupTags.forEach { ignoredTagsSet.add(it.name) }
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
            row.maximumSize = Dimension(Int.MAX_VALUE, 30)
            
            row.add(JBLabel(tagName), BorderLayout.CENTER)

            val restoreBtn = JButton(AllIcons.Actions.Back).apply {
                toolTipText = "Restore from Ignored"
                preferredSize = Dimension(24, 24)
                isContentAreaFilled = false
                isBorderPainted = false
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
