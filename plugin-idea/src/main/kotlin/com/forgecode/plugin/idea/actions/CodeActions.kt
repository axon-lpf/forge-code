package com.forgecode.plugin.idea.actions

import com.forgecode.plugin.idea.util.EditorUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 基础 Action — 获取选中代码并发送到聊天面板
 */
abstract class BaseCodeAction : AnAction() {

    /** 子类实现：将选中代码包装成用户消息 */
    abstract fun buildPrompt(selectedCode: String, language: String, fileName: String): String

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)

        val selectedCode = EditorUtil.getSelectedText(editor) ?: return
        val language = file?.language?.displayName ?: ""
        val fileName = file?.name ?: ""

        val prompt = buildPrompt(selectedCode, language, fileName)

        // 打开聊天面板并发送消息
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Forge Code")
        toolWindow?.show()

        // 通过 JCEF 发送消息到聊天面板
        EditorUtil.sendMessageToChat(project, prompt)
    }

    override fun update(e: AnActionEvent) {
        // 只有选中了文本才启用菜单项
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() ?: false
        e.presentation.isEnabled = hasSelection
    }
}

// ==================== 具体 Action ====================

/** 解释代码 */
class ExplainCodeAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        "请解释以下${if (language.isNotEmpty()) " $language " else ""}代码的功能和逻辑：\n\n```${language.lowercase()}\n$selectedCode\n```"
}

/** 优化代码 */
class OptimizeCodeAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        "请优化以下${if (language.isNotEmpty()) " $language " else ""}代码，提升性能、可读性和最佳实践：\n\n```${language.lowercase()}\n$selectedCode\n```"
}

/** 审查代码 */
class ReviewCodeAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        "请审查以下${if (language.isNotEmpty()) " $language " else ""}代码，找出潜在的 Bug、安全问题和改进点：\n\n```${language.lowercase()}\n$selectedCode\n```"
}

/** 生成单元测试 */
class GenerateTestAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        "请为以下${if (language.isNotEmpty()) " $language " else ""}代码生成完整的单元测试：\n\n```${language.lowercase()}\n$selectedCode\n```"
}
