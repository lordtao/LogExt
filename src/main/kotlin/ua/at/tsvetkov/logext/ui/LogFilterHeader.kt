package ua.at.tsvetkov.logext.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.awt.event.ActionListener
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Верхняя панель фильтрации (Устройство, Процесс, Фильтр тегов и Уровни).
 */
class LogFilterHeader(
    private val onDeviceChanged: (String?) -> Unit,
    private val onProcessChanged: (String?) -> Unit,
    private val onLevelsChanged: () -> Unit,
    private val onTagFilterClicked: () -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 10, 5)) {

    private val lampOn = IconLoader.getIcon("/icons/lamp_on.svg", javaClass)
    private val lampOff = IconLoader.getIcon("/icons/lamp_off.svg", javaClass)

    private val deviceModel = DefaultComboBoxModel<String>()
    private val processModel = DefaultComboBoxModel<String>()

    private val deviceCombo = ComboBox(deviceModel)
    private val processCombo = ComboBox(processModel)

    private val levelChecks = mutableMapOf<String, JBCheckBox>()

    private val tagFilterBtn = JButton("Tag filter", lampOff).apply {
        toolTipText = "Tag filter"
    }

    private var isUpdating = false

    private val deviceListener = ActionListener {
        if (!isUpdating) {
            onDeviceChanged(deviceCombo.selectedItem as? String)
        }
    }

    private val processListener = ActionListener {
        if (!isUpdating) {
            onProcessChanged(processCombo.selectedItem as? String)
        }
    }

    init {
        border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 1, 0)

        add(JBLabel("Device:"))
        add(deviceCombo)

        add(JBLabel("Process:"))
        add(processCombo)

        deviceCombo.addActionListener(deviceListener)
        processCombo.addActionListener(processListener)

        tagFilterBtn.addActionListener { onTagFilterClicked() }
        add(tagFilterBtn)

        add(JBLabel("Levels:"))
        listOf("V", "D", "I", "W", "E", "A").forEach { level ->
            val checkBox = JBCheckBox(level, true)
            checkBox.addActionListener { if (!isUpdating) onLevelsChanged() }
            levelChecks[level] = checkBox
            add(checkBox)
        }

        deviceModel.addElement("Loading devices...")
        processModel.addElement("All Processes")
    }

    fun updateDevices(devices: List<String>) {
        isUpdating = true
        val current = deviceCombo.selectedItem as? String
        deviceModel.removeAllElements()
        devices.forEach { deviceModel.addElement(it) }
        if (current != null && devices.contains(current)) {
            deviceCombo.selectedItem = current
        } else if (devices.isNotEmpty()) {
            deviceCombo.selectedIndex = 0
        }
        isUpdating = false
    }

    fun updateProcesses(processes: List<String>, preferredProcess: String? = null) {
        isUpdating = true
        val current = processCombo.selectedItem as? String
        processModel.removeAllElements()
        processModel.addElement("All Processes")
        processes.forEach { processModel.addElement(it) }

        when {
            current != null && (processes.contains(current) || current == "All Processes") -> {
                processCombo.selectedItem = current
            }
            preferredProcess != null && processes.contains(preferredProcess) -> {
                processCombo.selectedItem = preferredProcess
            }
            processes.isNotEmpty() -> {
                processCombo.selectedItem = processes[0]
            }
            else -> {
                processCombo.selectedItem = "All Processes"
            }
        }
        isUpdating = false
    }

    fun getSelectedProcess(): String? = processCombo.selectedItem as? String

    fun getSelectedDevice(): String? = deviceCombo.selectedItem as? String

    fun isLevelSelected(levelChar: String): Boolean = levelChecks[levelChar]?.isSelected ?: true

    /**
     * Обновляет иконку кнопки фильтра в зависимости от активности фильтрации.
     * @param isActive true, если часть тегов отфильтрована (выключена).
     */
    fun setTagFilterActive(isActive: Boolean) {
        tagFilterBtn.icon = if (isActive) lampOn else lampOff
    }
}
