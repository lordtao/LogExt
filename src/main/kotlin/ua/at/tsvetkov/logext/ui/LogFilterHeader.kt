package ua.at.tsvetkov.logext.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.awt.event.ActionListener
import javax.swing.JButton
import javax.swing.JPanel
import com.intellij.openapi.util.IconLoader

/**
 * Заголовок панели фильтров.
 */
class LogFilterHeader(
    val onDeviceChanged: (String?) -> Unit,
    val onProcessChanged: (String?) -> Unit,
    val onLevelsChanged: () -> Unit,
    val onTagFilterClicked: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 10, 5)) {

    private val deviceCombo = ComboBox<String>()
    private val processCombo = ComboBox<String>()
    private val levelChecks = mutableMapOf<String, JBCheckBox>()
    private val tagFilterBtn = JButton("Tag filter")

    private val lampOn = IconLoader.getIcon("/icons/lamp_on.svg", LogFilterHeader::class.java)
    private val lampOff = IconLoader.getIcon("/icons/lamp_off.svg", LogFilterHeader::class.java)

    private var isUpdating = false

    init {
        border = JBUI.Borders.empty(2, 5)

        add(JBLabel("Device:"))
        deviceCombo.addItem("Loading devices...")
        deviceCombo.addActionListener { 
            if (!isUpdating) onDeviceChanged(getSelectedDevice()) 
        }
        add(deviceCombo)

        add(JBLabel("Process:"))
        processCombo.addItem("All Processes")
        processCombo.addActionListener { 
            if (!isUpdating) onProcessChanged(getSelectedProcess()) 
        }
        add(processCombo)

        add(tagFilterBtn)
        tagFilterBtn.icon = lampOff
        tagFilterBtn.addActionListener { onTagFilterClicked() }

        add(JBLabel("Levels:"))
        val levels = listOf("V", "D", "I", "W", "E", "A")
        val listener = ActionListener { onLevelsChanged() }
        levels.forEach { level ->
            val cb = JBCheckBox(level, true)
            cb.addActionListener(listener)
            levelChecks[level] = cb
            add(cb)
        }
    }

    fun hideDeviceAndProcessSelectors() {
        deviceCombo.isVisible = false
        processCombo.isVisible = false
        components.filterIsInstance<JBLabel>().forEach { 
            if ((it.text == "Device:") || (it.text == "Process:")) it.isVisible = false
        }
    }

    fun updateDevices(devices: List<String>) {
        val selected = getSelectedDevice()
        isUpdating = true
        try {
            deviceCombo.removeAllItems()
            if (devices.isEmpty()) {
                deviceCombo.addItem("No devices")
            } else {
                devices.forEach { deviceCombo.addItem(it) }
                if (selected != null && devices.contains(selected)) {
                    deviceCombo.selectedItem = selected
                }
            }
        } finally {
            isUpdating = false
        }
    }

    fun updateProcesses(processes: List<String>, preferred: String? = null) {
        val selected = preferred ?: getSelectedProcess()
        isUpdating = true
        try {
            processCombo.removeAllItems()
            processCombo.addItem("All Processes")
            processes.forEach { processCombo.addItem(it) }
            if (selected != null && (processes.contains(selected) || selected == "All Processes")) {
                processCombo.selectedItem = selected
            }
        } finally {
            isUpdating = false
        }
    }

    fun getSelectedDevice(): String? = deviceCombo.selectedItem as? String
    fun getSelectedProcess(): String? = processCombo.selectedItem as? String
    fun isLevelSelected(level: String): Boolean = levelChecks[level]?.isSelected ?: true

    fun setTagFilterActive(active: Boolean) {
        tagFilterBtn.icon = if (active) lampOn else lampOff
    }
}
