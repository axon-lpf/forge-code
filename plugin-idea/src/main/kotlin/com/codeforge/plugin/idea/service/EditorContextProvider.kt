package com.codeforge.plugin.idea.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

/**
 * 编辑器上下文提供者 — 自动采集 IDE 当前状态
 *
 * 采集内容：
 *  1. 当前活跃文件（路径、语言）
 *  2. 光标位置（行号、列号）
 *  3. 选中的代码（如有）
 *  4. 光标附近上下文（前后各 50 行）
 *  5. 打开的文件 Tab 列表
 */
object EditorContextProvider {

    private val log = logger<EditorContextProvider>()

    /** 光标附近上下文取多少行 */
    private const val CONTEXT_LINES_BEFORE = 50
    private const val CONTEXT_LINES_AFTER = 50
    /** 上下文最大字符数（防止超大文件） */
    private const val MAX_CONTEXT_CHARS = 8000

    /** 采集到的编辑器上下文 */
    data class EditorContext(
        val activeFilePath: String?,        // 当前文件相对路径
        val activeFileName: String?,        // 当前文件名
        val language: String?,              // 语言
        val cursorLine: Int?,               // 光标行号（1-based）
        val cursorColumn: Int?,             // 光标列号（1-based）
        val selectedCode: String?,          // 选中的代码
        val surroundingCode: String?,       // 光标附近代码
        val surroundingStartLine: Int?,     // surrounding 起始行（1-based）
        val openFiles: List<String>         // 打开的文件列表
    )

    // ==================== 公开 API ====================

    /**
     * 采集当前编辑器上下文
     */
    fun collectContext(project: Project): EditorContext {
        return try {
            ReadAction.compute<EditorContext, Exception> {
                doCollect(project)
            }
        } catch (e: Exception) {
            log.debug("采集编辑器上下文失败: ${e.message}")
            EditorContext(null, null, null, null, null, null, null, null, emptyList())
        }
    }

    /**
     * 将上下文格式化为 LLM 可理解的文本块
     * 适合注入到 system prompt 或 user message 前
     */
    fun formatAsPrompt(ctx: EditorContext): String {
        if (ctx.activeFilePath == null) return ""

        val sb = StringBuilder()
        sb.appendLine("\n---")
        sb.appendLine("【当前编辑器上下文】")

        // 活跃文件
        sb.appendLine("▸ 当前文件: `${ctx.activeFilePath}`${ctx.language?.let { " ($it)" } ?: ""}")

        // 光标位置
        if (ctx.cursorLine != null) {
            sb.appendLine("▸ 光标位置: 第 ${ctx.cursorLine} 行, 第 ${ctx.cursorColumn ?: 1} 列")
        }

        // 打开的文件列表
        if (ctx.openFiles.isNotEmpty()) {
            sb.appendLine("▸ 打开的文件: ${ctx.openFiles.joinToString(", ") { "`$it`" }}")
        }

        // 选中的代码
        if (!ctx.selectedCode.isNullOrBlank()) {
            val lang = ctx.language?.lowercase() ?: ""
            sb.appendLine("\n▸ 选中的代码:")
            sb.appendLine("```$lang")
            sb.appendLine(ctx.selectedCode.take(MAX_CONTEXT_CHARS))
            sb.appendLine("```")
        }

        // 光标附近上下文（仅在没有选中代码时显示，避免冗余）
        if (ctx.selectedCode.isNullOrBlank() && !ctx.surroundingCode.isNullOrBlank()) {
            val lang = ctx.language?.lowercase() ?: ""
            val startLine = ctx.surroundingStartLine ?: 1
            sb.appendLine("\n▸ 光标附近代码 (从第 $startLine 行开始):")
            sb.appendLine("```$lang")
            sb.appendLine(ctx.surroundingCode)
            sb.appendLine("```")
        }

        sb.appendLine("---")
        return sb.toString()
    }

    /**
     * 一步完成：采集 + 格式化
     * 返回空字符串表示没有可用上下文
     */
    fun getFormattedContext(project: Project): String {
        val ctx = collectContext(project)
        return formatAsPrompt(ctx)
    }

    // ==================== 内部实现 ====================

    private fun doCollect(project: Project): EditorContext {
        val editorManager = FileEditorManager.getInstance(project)
        val editor: Editor? = editorManager.selectedTextEditor
        val roots = ProjectRootManager.getInstance(project).contentRoots

        // 1. 打开的文件列表
        val openFiles = editorManager.openFiles.mapNotNull { vf ->
            roots.firstNotNullOfOrNull { root ->
                VfsUtil.getRelativePath(vf, root)
            } ?: vf.name
        }

        // 没有活跃编辑器
        if (editor == null) {
            return EditorContext(null, null, null, null, null, null, null, null, openFiles)
        }

        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)

        // 2. 文件路径
        val relativePath = virtualFile?.let { vf ->
            roots.firstNotNullOfOrNull { root -> VfsUtil.getRelativePath(vf, root) }
        } ?: virtualFile?.name

        // 3. 语言
        val language = detectLanguage(virtualFile?.name ?: "")

        // 4. 光标位置
        val caretModel = editor.caretModel
        val cursorLine = caretModel.logicalPosition.line + 1    // 1-based
        val cursorColumn = caretModel.logicalPosition.column + 1

        // 5. 选中代码
        val selectedCode = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }

        // 6. 光标附近上下文
        val totalLines = document.lineCount
        val startLine = maxOf(0, caretModel.logicalPosition.line - CONTEXT_LINES_BEFORE)
        val endLine = minOf(totalLines - 1, caretModel.logicalPosition.line + CONTEXT_LINES_AFTER)

        val surroundingCode = if (totalLines > 0) {
            val startOffset = document.getLineStartOffset(startLine)
            val endOffset = document.getLineEndOffset(endLine)
            document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
                .take(MAX_CONTEXT_CHARS)
        } else null

        return EditorContext(
            activeFilePath = relativePath,
            activeFileName = virtualFile?.name,
            language = language,
            cursorLine = cursorLine,
            cursorColumn = cursorColumn,
            selectedCode = selectedCode,
            surroundingCode = surroundingCode,
            surroundingStartLine = startLine + 1,  // 1-based
            openFiles = openFiles
        )
    }

    private fun detectLanguage(fileName: String): String? {
        return when {
            fileName.endsWith(".kt")   -> "Kotlin"
            fileName.endsWith(".java") -> "Java"
            fileName.endsWith(".py")   -> "Python"
            fileName.endsWith(".js")   -> "JavaScript"
            fileName.endsWith(".ts")   -> "TypeScript"
            fileName.endsWith(".tsx")  -> "TypeScript React"
            fileName.endsWith(".jsx")  -> "JavaScript React"
            fileName.endsWith(".go")   -> "Go"
            fileName.endsWith(".rs")   -> "Rust"
            fileName.endsWith(".cpp") || fileName.endsWith(".cc") -> "C++"
            fileName.endsWith(".c")    -> "C"
            fileName.endsWith(".swift") -> "Swift"
            fileName.endsWith(".xml")  -> "XML"
            fileName.endsWith(".yaml") || fileName.endsWith(".yml") -> "YAML"
            fileName.endsWith(".json") -> "JSON"
            fileName.endsWith(".md")   -> "Markdown"
            fileName.endsWith(".sql")  -> "SQL"
            fileName.endsWith(".sh")   -> "Shell"
            fileName.endsWith(".html") -> "HTML"
            fileName.endsWith(".css")  -> "CSS"
            fileName.endsWith(".vue")  -> "Vue"
            fileName.endsWith(".dart") -> "Dart"
            fileName.endsWith(".rb")   -> "Ruby"
            fileName.endsWith(".php")  -> "PHP"
            else -> null
        }
    }
}

