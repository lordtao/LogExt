package ua.at.tsvetkov.logext.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColorPanel
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import ua.at.tsvetkov.logext.services.LogCatGlobalSettingsService
import java.awt.*
import java.awt.event.ActionListener
import javax.swing.*

/**
 * Диалоговое окно настроек TAO LogExt.
 */
class LogSettingsDialog(project: Project) : DialogWrapper(project) {

    private val globalSettings = LogCatGlobalSettingsService.getInstance()
    private val colorPanels = mutableMapOf<String, Pair<ColorPanel, ColorPanel>>()
    
    // Startup Settings
    private val clearLogOnStartCheck = JBCheckBox("Clear log on application start", globalSettings.state.clearLogOnStart)
    private val openOnStartCheck = JBCheckBox("Open tool window on application start", globalSettings.state.openOnStart)
    
    // Log Line View Settings
    private val showDateCheck = JBCheckBox("Date", globalSettings.state.showDate)
    private val showTimeCheck = JBCheckBox("Time", globalSettings.state.showTime)
    private val showMillisCheck = JBCheckBox("Milliseconds", globalSettings.state.showMillis)
    private val showPidCheck = JBCheckBox("Process ID", globalSettings.state.showPid)
    private val showTidCheck = JBCheckBox("Thread ID", globalSettings.state.showTid)
    private val tagWidthSpinner = JSpinner(SpinnerNumberModel(globalSettings.state.tagWidth, 0, 100, 1))
    
    private val formatPreviewLabel = JBLabel().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
            JBUI.Borders.empty(5)
        )
    }

    init {
        title = "TAO LogExt Settings"
        init()
        updateFormatPreview()
        
        val updatePreviewListener = ActionListener { updateFormatPreview() }
        showDateCheck.addActionListener(updatePreviewListener)
        showTimeCheck.addActionListener(updatePreviewListener)
        showMillisCheck.addActionListener(updatePreviewListener)
        showPidCheck.addActionListener(updatePreviewListener)
        showTidCheck.addActionListener(updatePreviewListener)
        tagWidthSpinner.addChangeListener { updateFormatPreview() }
    }

    override fun createCenterPanel(): JComponent {
        val formBuilder = FormBuilder.createFormBuilder()

        // 1. Цветовые настройки
        formBuilder.addComponent(TitledSeparator("Color Settings"))
        formBuilder.addComponent(createColorSettingsPanel())

        // 2. Настройки вида строки
        formBuilder.addComponent(JBUI.Borders.emptyTop(10).wrap(TitledSeparator("Log Line View")))
        
        val checkboxesPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        checkboxesPanel.add(showDateCheck)
        checkboxesPanel.add(showTimeCheck)
        checkboxesPanel.add(showMillisCheck)
        checkboxesPanel.add(showPidCheck)
        checkboxesPanel.add(showTidCheck)
        formBuilder.addComponent(checkboxesPanel)

        val tagWidthPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        tagWidthPanel.add(JBLabel("Tag width:"))
        tagWidthPanel.add(tagWidthSpinner)
        formBuilder.addComponent(tagWidthPanel)

        formBuilder.addLabeledComponent("Preview:", formatPreviewLabel)

        // 3. Настройки старта
        formBuilder.addComponent(JBUI.Borders.emptyTop(10).wrap(TitledSeparator("Startup Settings")))
        formBuilder.addComponent(clearLogOnStartCheck)
        formBuilder.addComponent(openOnStartCheck)

        return formBuilder.panel
    }

    private fun createColorSettingsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(2, 5)

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
            val attrs = globalSettings.getLevelAttributes(levelKey)

            gbc.gridy = index + 1
            gbc.weightx = 0.0

            gbc.gridx = 0
            panel.add(JBLabel(levelName), gbc)

            gbc.gridx = 1
            val fgPanel = ColorPanel()
            attrs.foregroundColor?.let { fgPanel.selectedColor = Color.decode(it) }
            panel.add(fgPanel, gbc)

            gbc.gridx = 2
            val bgPanel = ColorPanel()
            attrs.backgroundColor?.let { bgPanel.selectedColor = Color.decode(it) }
            panel.add(bgPanel, gbc)

            gbc.gridx = 3
            gbc.weightx = 1.0
            val exampleLabel = JBLabel(" Sample log message ").apply {
                isOpaque = true
                horizontalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(150, 24)
                updateColorExample(this, fgPanel.selectedColor, bgPanel.selectedColor)
            }
            
            fgPanel.addActionListener { updateColorExample(exampleLabel, fgPanel.selectedColor, bgPanel.selectedColor) }
            bgPanel.addActionListener { updateColorExample(exampleLabel, fgPanel.selectedColor, bgPanel.selectedColor) }
            
            colorPanels[levelKey] = fgPanel to bgPanel
            panel.add(exampleLabel, gbc)
        }

        return panel
    }

    private fun updateColorExample(label: JBLabel, fg: Color?, bg: Color?) {
        label.foreground = fg ?: JBUI.CurrentTheme.Label.foreground()
        label.background = bg ?: JBUI.CurrentTheme.CustomFrameDecorations.paneBackground()
    }

    private fun updateFormatPreview() {
        val sb = StringBuilder()
        if (showDateCheck.isSelected) sb.append("06-11 ")
        if (showTimeCheck.isSelected) {
            sb.append("18:30:26")
            if (showMillisCheck.isSelected) sb.append(".582")
            sb.append(" ")
        }
        if (showPidCheck.isSelected) sb.append("28025 ")
        if (showTidCheck.isSelected) sb.append("28054 ")
        
        sb.append("D ")
        
        val width = tagWidthSpinner.value as Int
        val tag = "GreedyScheduler"
        val formattedTag = if (width > 0) {
            if (tag.length > width) tag.substring(0, width)
            else tag.padEnd(width)
        } else tag
        
        sb.append("$formattedTag: Cancelling")
        
        formatPreviewLabel.text = sb.toString()
    }

    override fun doOKAction() {
        colorPanels.forEach { (level, panels) ->
            globalSettings.setLevelAttributes(level, panels.first.selectedColor, panels.second.selectedColor)
        }
        
        val state = globalSettings.state
        state.clearLogOnStart = clearLogOnStartCheck.isSelected
        state.openOnStart = openOnStartCheck.isSelected
        state.showDate = showDateCheck.isSelected
        state.showTime = showTimeCheck.isSelected
        state.showMillis = showMillisCheck.isSelected
        state.showPid = showPidCheck.isSelected
        state.showTid = showTidCheck.isSelected
        state.tagWidth = tagWidthSpinner.value as Int

        super.doOKAction()
    }
}
