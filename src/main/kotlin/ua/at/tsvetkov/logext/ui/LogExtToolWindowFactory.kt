package ua.at.tsvetkov.logext.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Фабрика для создания боковой панели LogExt.
 */
class LogExtToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val logExtPanel = LogExtPanel(project)
        val content = ContentFactory.getInstance().createContent(logExtPanel, "LogExt", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        
        Disposer.register(toolWindow.contentManager, logExtPanel)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun isApplicable(project: Project): Boolean = true

    override fun shouldBeAvailable(project: Project): Boolean = true
}
