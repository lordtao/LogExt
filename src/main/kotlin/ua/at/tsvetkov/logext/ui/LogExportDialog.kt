package ua.at.tsvetkov.logext.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import ua.at.tsvetkov.logext.services.LogCatGlobalSettingsService
import java.io.File
import javax.swing.JComponent

/**
 * Диалоговое окно для настройки экспорта логов.
 */
class LogExportDialog(private val project: Project) : DialogWrapper(project) {

    private val globalSettings = LogCatGlobalSettingsService.getInstance()
    private val pathField = TextFieldWithBrowseButton()
    private val minimizeCheck = JBCheckBox("Minimize for AI", globalSettings.state.minimizeForAi)

    init {
        title = "Export Logs"
        pathField.text = globalSettings.state.lastExportPath ?: ""
        
        pathField.addActionListener {
            val descriptor = FileSaverDescriptor("Select Export File", "Enter the name of the file to save logs to", "txt")
            val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            
            val currentFile = File(pathField.text)
            val baseDir = if (currentFile.parentFile?.exists() == true) {
                LocalFileSystem.getInstance().findFileByIoFile(currentFile.parentFile)
            } else {
                VfsUtil.getUserHomeDir()
            }
            
            val fileWrapper = saveDialog.save(baseDir, currentFile.name.ifEmpty { "log.txt" })
            fileWrapper?.let {
                pathField.text = it.file.absolutePath
            }
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Export to:", pathField)
            .addComponent(minimizeCheck)
            .panel
    }

    fun getExportPath(): String = pathField.text

    fun isMinimizeForAi(): Boolean = minimizeCheck.isSelected

    override fun doOKAction() {
        val state = globalSettings.state
        state.lastExportPath = pathField.text
        state.minimizeForAi = minimizeCheck.isSelected
        super.doOKAction()
    }
}
