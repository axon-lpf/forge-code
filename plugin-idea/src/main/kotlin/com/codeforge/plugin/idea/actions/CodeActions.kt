package com.codeforge.plugin.idea.actions

import com.codeforge.plugin.idea.util.EditorUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 基础 Action — 获取选中代码并发送到聊天面板
 * 抽象基类，定义了处理选中代码并发送到聊天面板的通用流程
 */
abstract class BaseCodeAction : AnAction() {

    /** 子类实现：将选中代码包装成用户消息 */
    abstract fun buildPrompt(selectedCode: String, language: String, fileName: String): String

    /** 是否需要选中代码才可用（默认 true） */
    open fun requiresSelection(): Boolean = true

    override fun actionPerformed(e: AnActionEvent) {
        // 获取当前项目，如果没有项目则直接返回，因为插件需要项目上下文
        val project = e.project ?: return
        // 获取当前编辑器实例，用于获取选中的代码
        val editor = e.getData(CommonDataKeys.EDITOR)
        // 获取当前文件，用于获取语言类型和文件名
        val file = e.getData(CommonDataKeys.PSI_FILE)

        // 获取选中的代码，如果编辑器存在则获取选中文本，否则为空字符串
        val selectedCode = editor?.let { EditorUtil.getSelectedText(it) } ?: ""
        // 获取文件的语言类型（如 Kotlin、Java 等）
        val language = file?.language?.displayName ?: ""
        // 获取文件名，用于在提示中提供上下文
        val fileName = file?.name ?: ""

        // 如果该 Action 需要选中代码但当前没有选中，则直接返回不执行后续操作
        if (requiresSelection() && selectedCode.isBlank()) return

        // 调用子类实现的构建提示方法，生成发送给 AI 的完整提示
        val prompt = buildPrompt(selectedCode, language, fileName)

        // 打开聊天面板并发送消息
        // 获取 CodeForge 工具窗口，用于显示聊天界面
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CodeForge")
        toolWindow?.show()  // 显示工具窗口，如果存在的话

        // 通过 JCEF 发送消息到聊天面板，触发 AI 处理
        EditorUtil.sendMessageToChat(project, prompt)
    }

    override fun update(e: AnActionEvent) {
        // 更新 Action 的可用状态，在菜单/工具栏中显示为启用或禁用
        if (requiresSelection()) {
            // 如果需要选中代码，则检查当前编辑器是否有选中文本
            val editor = e.getData(CommonDataKeys.EDITOR)
            val hasSelection = editor?.selectionModel?.hasSelection() ?: false
            e.presentation.isEnabled = hasSelection  // 有选中文本才启用
        } else {
            // 不需要选中代码的 Action 始终启用
            e.presentation.isEnabled = true
        }
    }
}

// ==================== 具体 Action ====================

/** Chat — 打开聊天面板（不需要选中代码） */
class ChatAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        // 简单的聊天 Action，仅打开聊天面板，不发送任何代码
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CodeForge")
        toolWindow?.show()  // 直接显示聊天面板，让用户自由输入
    }

    override fun update(e: AnActionEvent) {
        // 只要有项目上下文就启用该 Action
        e.presentation.isEnabled = e.project != null
    }
}

/** Run Custom Prompt Template — 自定义 Prompt 模板 */
class CustomPromptAction : BaseCodeAction() {

    override fun requiresSelection(): Boolean = false  // 自定义 Prompt 不需要选中代码

    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String {
        // 弹出输入框让用户输入自定义 Prompt，提供最大的灵活性
        val customPrompt = Messages.showInputDialog(
            "请输入自定义 Prompt 指令：",  // 提示用户输入
            "CodeForge — Custom Prompt",  // 对话框标题
            Messages.getQuestionIcon(),    // 使用问号图标
            "",                           // 初始值为空
            null                          // 无输入验证器
        )
        // 如果用户取消输入或输入为空，返回空字符串
        if (customPrompt.isNullOrBlank()) return ""

        // 如果有选中代码，将代码块附加到自定义 Prompt 后面
        // 这样用户可以对特定代码执行自定义指令
        return if (selectedCode.isNotBlank()) {
            "$customPrompt\n\n```${language.lowercase()}\n$selectedCode\n```"
        } else {
            // 没有选中代码时，只发送自定义 Prompt
            customPrompt
        }
    }

    // 重写 actionPerformed 以添加空提示检查，避免发送空消息
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)

        val selectedCode = editor?.let { EditorUtil.getSelectedText(it) } ?: ""
        val language = file?.language?.displayName ?: ""
        val fileName = file?.name ?: ""

        val prompt = buildPrompt(selectedCode, language, fileName)
        if (prompt.isBlank()) return  // 如果提示为空，不执行后续操作

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CodeForge")
        toolWindow?.show()
        EditorUtil.sendMessageToChat(project, prompt)
    }
}

/** Find Problem — 查找代码中的潜在问题 */
class FindProblemAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        // 构建专门用于代码问题检测的提示模板
        // 要求 AI 从多个维度检查代码问题，并提供严重程度分级
        """请仔细检查以下${langLabel(language)}代码，找出所有潜在问题，包括：
1. Bug 和逻辑错误
2. 空指针 / 越界 / 异常处理缺失
3. 安全漏洞（SQL注入、XSS、敏感信息泄露等）
4. 性能问题（资源泄露、死循环、低效算法）
5. 并发安全问题

请按严重程度分级：🔴 严重 / 🟡 警告 / 🟢 建议

```${language.lowercase()}
$selectedCode
```"""
}

/** Explain — 解释代码 */
class ExplainCodeAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        // 简单的解释代码提示，要求 AI 详细说明代码的功能和设计意图
        "请详细解释以下${langLabel(language)}代码的功能、逻辑和设计意图：\n\n```${language.lowercase()}\n$selectedCode\n```"
}

/** Optimize — 优化代码 */
class OptimizeCodeAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        // 构建代码优化提示，要求从多个角度改进代码
        // 并需要 AI 提供优化后的代码和改动说明
        """请优化以下${langLabel(language)}代码，从以下角度改进：
1. 性能优化
2. 可读性和代码结构
3. 最佳实践和规范
4. 异常处理

请给出优化后的完整代码，并简要说明每项改动的原因。

```${language.lowercase()}
$selectedCode
```"""
}

/** Generate — 根据上下文生成代码 (Ctrl+Alt+K) */
class GenerateAction : BaseCodeAction() {

    override fun requiresSelection(): Boolean = false  // 生成代码不需要选中，可以根据文件上下文生成

    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String {
        return if (selectedCode.isNotBlank()) {
            // 如果有选中代码，要求 AI 根据选中代码的上下文续写
            "请根据以下${langLabel(language)}代码的上下文，续写 / 补全代码：\n\n```${language.lowercase()}\n$selectedCode\n```"
        } else {
            // 没有选中代码时，根据文件和语言上下文生成代码
            "请根据文件 `$fileName`（${language}）的上下文，生成合理的代码。"
        }
    }
}

/** Add Comments — 添加注释 (Ctrl+Alt+/) */
class AddCommentsAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        // 专门用于添加代码注释的提示模板
        // 强调注释要说明"为什么"而不仅仅是"做了什么"
        """请为以下${langLabel(language)}代码添加清晰、简洁的行内注释。要求：
1. 为每个关键代码段添加中文注释
2. 注释说明"为什么"而不仅仅是"做了什么"
3. 不改变原始代码逻辑
4. 返回添加注释后的完整代码

```${language.lowercase()}
$selectedCode
```"""
}

/** Add Docstring — 添加文档注释 (Ctrl+Alt+引号) */
class AddDocstringAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        // 用于添加正式文档注释的提示模板
        // 要求生成符合语言规范的文档注释（Javadoc/KDoc等）
        """请为以下${langLabel(language)}代码添加规范的文档注释（Javadoc / KDoc / Docstring / JSDoc 等）。要求：
1. 为每个类、方法/函数添加文档注释
2. 包含 @param、@return、@throws 等标签（如适用）
3. 描述方法的功能、参数含义和返回值
4. 不改变原始代码逻辑
5. 返回添加文档注释后的完整代码

```${language.lowercase()}
$selectedCode
```"""
}

/** Generate Unit Test — 生成单元测试 */
class GenerateTestAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        // 生成单元测试的提示模板
        // 要求覆盖正常路径和边界情况，使用主流测试框架
        """请为以下${langLabel(language)}代码生成完整的单元测试。要求：
1. 覆盖正常路径和边界情况
2. 使用该语言主流测试框架（JUnit / pytest / Jest 等）
3. 包含必要的 mock 和测试数据
4. 每个测试方法有清晰的命名和注释

```${language.lowercase()}
$selectedCode
```"""
}

/** Review Code — 审查代码 */
class ReviewCodeAction : BaseCodeAction() {
    override fun buildPrompt(selectedCode: String, language: String, fileName: String): String =
        // 全面的代码审查提示模板
        // 从代码质量、潜在 Bug、安全风险、性能问题、最佳实践五个维度审查
        """请对以下${langLabel(language)}代码进行全面的代码审查：
1. **代码质量**：命名、结构、可读性
2. **潜在 Bug**：空指针、边界条件、异常处理
3. **安全风险**：注入、泄露、权限问题
4. **性能问题**：不必要的计算、资源泄露
5. **最佳实践**：是否符合语言/框架规范

每个问题请标明：🔴 严重 / 🟡 警告 / 🟢 建议，并给出改进代码。

```${language.lowercase()}
$selectedCode
```"""
}

// ==================== 工具函数 ====================

/**
 * 语言标签格式化函数
 * 在语言名称前后添加空格，用于美化提示文本中的显示
 * 如果语言为空，则返回空字符串
 */
private fun langLabel(language: String): String =
    if (language.isNotBlank()) " $language " else ""

