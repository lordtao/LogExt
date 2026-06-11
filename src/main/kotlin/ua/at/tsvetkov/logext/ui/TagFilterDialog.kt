package ua.at.tsvetkov.logext.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import ua.at.tsvetkov.logext.models.TagInfo
import java.awt.BorderLayout
import javax.swing.*

/**
 * Диалоговое окно фильтрации по тегам.
 */
class TagFilterDialog(
    project: Project,
    private val tags: List<TagInfo>
) : DialogWrapper(project) {

    private val checkBoxes = mutableListOf<JBCheckBox>()

    init {
        title = "Filter by Tags"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val listPanel = JPanel()
        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)

        // Сортировка: выбранные вверх
        val sortedTags = tags.sortedWith(compareByDescending<TagInfo> { it.isSelected }.thenBy { it.name })

        sortedTags.forEach { tag ->
            val checkBox = JBCheckBox(tag.name, tag.isSelected)
            if (!tag.isPresentInCurrentLog && tag.isSelected) {
                checkBox.foreground = JBColor.RED // Выделяем цветом отсутствующие, но ранее выбранные теги
            }
            checkBox.addActionListener { tag.isSelected = checkBox.isSelected }
            listPanel.add(checkBox)
            checkBoxes.add(checkBox)
        }

        val scrollPane = JBScrollPane(listPanel)
        scrollPane.preferredSize = JBUI.size(400, 500)
        panel.add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel()
        val selectAll = JButton("Select All")
        val clearAll = JButton("Clear All")

        selectAll.addActionListener {
            checkBoxes.forEach { it.isSelected = true }
            tags.forEach { it.isSelected = true }
        }

        clearAll.addActionListener {
            checkBoxes.forEach { it.isSelected = false }
            tags.forEach { it.isSelected = false }
        }

        buttonPanel.add(selectAll)
        buttonPanel.add(clearAll)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }
}
