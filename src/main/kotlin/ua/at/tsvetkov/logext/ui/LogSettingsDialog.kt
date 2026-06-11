package ua.at.tsvetkov.logext.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColorPanel
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import ua.at.tsvetkov.logext.services.LogCatSettingsService
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Диалоговое окно настроек TAO LogExt.
 */
class LogSettingsDialog(project: Project) : DialogWrapper(project) {

    private val settings = LogCatSettingsService.getInstance(project)
    private val colorPanels = mutableMapOf<String, Pair<ColorPanel, ColorPanel>>()
    private val clearLogOnStartCheck = JBCheckBox("Clear log on application start", settings.getState().clearLogOnStart)
    private val logFormatField = JBTextField(settings.getState().logFormat).apply { isEnabled = false }

    init {
        title = "TAO LogExt Settings"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val formBuilder = FormBuilder.createFormBuilder()

        // 1. Цветовые настройки
        formBuilder.addComponent(TitledSeparator("Color Settings"))
        formBuilder.addComponent(createColorSettingsPanel())

        // 2. Настройки вида строки
        formBuilder.addComponent(JBUI.Borders.emptyTop(10).wrap(TitledSeparator("Log Line View (Experimental)")))
        formBuilder.addLabeledComponent("Log format:", logFormatField)

        // 3. Настройки старта
        formBuilder.addComponent(JBUI.Borders.emptyTop(10).wrap(TitledSeparator("Startup Settings")))
        formBuilder.addComponent(clearLogOnStartCheck)

        return formBuilder.panel
    }

    private fun createColorSettingsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(2, 5)

        // Заголовки колонок
        val headers = listOf("Level", "Foreground", "Background", "Example")
        headers.forEachIndexed { i, text ->
            gbc.gridx = i
            gbc.gridy = 0
            gbc.weightx = if (i == 3) 1.0 else 0.0
            panel.add(JBLabel(text).apply { 
                font = font.deriveFont(Font.BOLD)
                horizontalAlignment = SwingConstants.CENTER
            }, gbc)
        }

        val levels = linkedMapOf(
            "V" to "Verbose",
            "D" to "Debug",
            "I" to "Info",
            "W" to "Warning",
            "E" to "Error",
            "A" to "Assert"
        )

        levels.entries.forEachIndexed { index, entry ->
            val levelKey = entry.key
            val levelName = entry.value
            val attrs = settings.getLevelAttributes(levelKey)

            gbc.gridy = index + 1
            gbc.weightx = 0.0

            // Level Name
            gbc.gridx = 0
            panel.add(JBLabel(levelName), gbc)

            // Foreground
            gbc.gridx = 1
            val fgPanel = ColorPanel()
            attrs.foregroundColor?.let { fgPanel.selectedColor = Color.decode(it) }
            panel.add(fgPanel, gbc)

            // Background
            gbc.gridx = 2
            val bgPanel = ColorPanel()
            attrs.backgroundColor?.let { bgPanel.selectedColor = Color.decode(it) }
            panel.add(bgPanel, gbc)

            // Example
            gbc.gridx = 3
            gbc.weightx = 1.0
            val exampleLabel = JBLabel(" Sample log message ").apply {
                isOpaque = true
                horizontalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(150, 24)
                updateExample(this, fgPanel.selectedColor, bgPanel.selectedColor)
            }
            
            fgPanel.addActionListener { updateExample(exampleLabel, fgPanel.selectedColor, bgPanel.selectedColor) }
            bgPanel.addActionListener { updateExample(exampleLabel, fgPanel.selectedColor, bgPanel.selectedColor) }
            
            colorPanels[levelKey] = fgPanel to bgPanel
            panel.add(exampleLabel, gbc)
        }

        return panel
    }

    private fun updateExample(label: JBLabel, fg: Color?, bg: Color?) {
        label.foreground = fg ?: JBUI.CurrentTheme.Label.foreground()
        label.background = bg ?: JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
    }

    override fun doOKAction() {
        colorPanels.forEach { (level, panels) ->
            settings.setLevelAttributes(level, panels.first.selectedColor, panels.second.selectedColor)
        }
        settings.getState().clearLogOnStart = clearLogOnStartCheck.isSelected
        super.doOKAction()
    }
}
