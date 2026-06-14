package ua.at.tsvetkov.logext.ui

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Фильтр для поиска паттернов FileName.kt:123 и создания кликабельных ссылок.
 */
class LogSourceLinkFilter(private val project: Project) : Filter {

    private val pattern = Regex("""(\b[\w-]+\.(?:kt|java)):(\d+)\b""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = pattern.find(line) ?: return null

        val fileName = match.groupValues[1]
        val lineNumber = match.groupValues[2].toIntOrNull() ?: return null

        val startOffset = entireLength - line.length + match.range.first
        val endOffset = entireLength - line.length + match.range.last + 1

        val virtualFile: VirtualFile? = ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            val files = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
            files.firstOrNull()
        }
        
        if (virtualFile == null) return null

        val hyperlinkInfo = OpenFileHyperlinkInfo(project, virtualFile, lineNumber - 1)

        return Filter.Result(startOffset, endOffset, hyperlinkInfo)
    }
}
