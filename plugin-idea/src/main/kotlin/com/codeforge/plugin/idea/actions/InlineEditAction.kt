package com.codeforge.plugin.idea.actions

import com.codeforge.plugin.idea.toolwindow.InlineEditPopup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * A7：行内 AI 编辑 Action
 *
 * 触发方式：
 *  - 快捷键 Alt+A（选中代码时生效）
 *  - 编辑器右键菜单 → CodeForge → Inline Edit
 *
 * 流程：选中代码 → Alt+A → InlineEditPopup 弹窗 → AI 修改 → InlineDiff 展示
 */
class InlineEditAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return

        val selectedText = selectionModel.selectedText ?: return
        val selectionStart = selectionModel.selectionStart
        val selectionEnd = selectionModel.selectionEnd

        InlineEditPopup(
            project = project,
            editor = editor,
            virtualFile = virtualFile,
            selectedText = selectedText,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd
        ).show()
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() == true
    }
}
