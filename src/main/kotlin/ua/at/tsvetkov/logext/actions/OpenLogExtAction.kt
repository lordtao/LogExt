package ua.at.tsvetkov.logext.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Действие для открытия окна TAO LogExt из меню.
 */
class OpenLogExtAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("TAO LogExt")
        toolWindow?.show()
    }
}
