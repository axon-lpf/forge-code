package com.codeforge.plugin.idea.actions

import com.codeforge.plugin.idea.toolwindow.InlineEditPopup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.SelectionModel
import com.intellij.openapi.project.Project

/**
 * A7：Inline Edit Action
 * 选中代码后按 Alt+A 触发行内编辑弹窗
 */
class InlineEditAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selection = editor?.selectionModel?.hasSelection() ?: false
        e.presentation.isEnabledAndVisible = selection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.getData(CommonDataKeys.PROJECT) ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection: SelectionModel = editor.selectionModel

        if (!selection.hasSelection()) return

        val selectedCode = selection.selectedText ?: return
        val language = detectLanguage(editor)

        InlineEditPopup.show(project, selectedCode, language)
    }

    private fun detectLanguage(editor: Editor): String {
        val document = editor.document
        val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
        return when (file?.extension?.lowercase()) {
            "kt", "kts" -> "kotlin"
            "java"     -> "java"
            "py"       -> "python"
            "js"       -> "javascript"
            "ts"       -> "typescript"
            "go"       -> "go"
            "rs"       -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c"        -> "c"
            "swift"    -> "swift"
            "xml"      -> "xml"
            "yaml", "yml" -> "yaml"
            "json"     -> "json"
            "html", "htm" -> "html"
            "css"      -> "css"
            "sql"      -> "sql"
            "md"       -> "markdown"
            else       -> ""
        }
    }
}
