package ua.at.tsvetkov.logext.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import ua.at.tsvetkov.logext.models.TagInfo
import ua.at.tsvetkov.logext.services.LogCatSettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*

/**
 * Диалоговое окно для фильтрации тегов с тремя группами: App, Other, Ignored.
 */
class TagFilterDialog(
    project: Project,
    private val allTags: List<TagInfo>
) : DialogWrapper(project) {

    private val settings = LogCatSettingsService.getInstance(project)
    private val workingTags = allTags.toMutableList()
    private val ignoredTagsList = settings.getState().ignoredTags.toMutableSet()
    
    // Панели для динамического обновления
    private val centerPanel = JPanel(GridLayout(1, 3, 10, 0))

    init {
        title = "Filter Tags"
        init()
        updatePanels()
    }

    override fun createCenterPanel(): JComponent {
        centerPanel.preferredSize = Dimension(1000, 600)
        return centerPanel
    }

    private fun updatePanels() {
        centerPanel.removeAll()
        
        val appTags = workingTags.filter { it.isApplicationTag && !ignoredTagsList.contains(it.name) }
            .sortedBy { it.name }
        val otherTags = workingTags.filter { !it.isApplicationTag && !ignoredTagsList.contains(it.name) }
            .sortedBy { it.name }
        val ignoredTags = ignoredTagsList.toList().sorted()

        centerPanel.add(createTagGroupPanel("Application Tags", appTags, true))
        centerPanel.add(createTagGroupPanel("Other Tags", otherTags, true))
        centerPanel.add(createIgnoredGroupPanel("Ignored Tags", ignoredTags))
        
        centerPanel.revalidate()
        centerPanel.repaint()
    }

    private fun createTagGroupPanel(title: String, groupTags: List<TagInfo>, showIgnoreButton: Boolean): JPanel {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel(title).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
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
                val ignoreBtn = JButton(AllIcons.Actions.Cancel).apply {
                    toolTipText = "Move to Ignored"
                    preferredSize = Dimension(24, 24)
                    isContentAreaFilled = false
                    isBorderPainted = false
                }
                ignoreBtn.addActionListener {
                    ignoredTagsList.add(tag.name)
                    updatePanels()
                }
                row.add(ignoreBtn, BorderLayout.EAST)
            }
            
            listPanel.add(row)
        }

        panel.add(JBScrollPane(listPanel), BorderLayout.CENTER)

        val buttons = JPanel(GridLayout(1, 2))
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
        buttons.add(selectAll)
        buttons.add(clearAll)
        panel.add(buttons, BorderLayout.SOUTH)

        return panel
    }

    private fun createIgnoredGroupPanel(title: String, groupTags: List<String>): JPanel {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel(title).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.emptyBottom(5)
        }, BorderLayout.NORTH)

        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)

        groupTags.forEach { tagName ->
            val row = JPanel(BorderLayout())
            row.maximumSize = Dimension(Int.MAX_VALUE, 30)
            
            row.add(JBLabel(tagName), BorderLayout.CENTER)

            val restoreBtn = JButton(AllIcons.General.Add).apply {
                toolTipText = "Restore from Ignored"
                preferredSize = Dimension(24, 24)
                isContentAreaFilled = false
                isBorderPainted = false
            }
            restoreBtn.addActionListener {
                ignoredTagsList.remove(tagName)
                updatePanels()
            }
            row.add(restoreBtn, BorderLayout.EAST)
            
            listPanel.add(row)
        }

        panel.add(JBScrollPane(listPanel), BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        // Сохраняем черный список
        settings.getState().ignoredTags = ignoredTagsList
        super.doOKAction()
    }
}
