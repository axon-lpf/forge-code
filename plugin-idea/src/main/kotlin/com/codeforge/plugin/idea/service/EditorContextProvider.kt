package com.codeforge.plugin.idea.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

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
        val openFiles: List<String>,        // 打开的文件列表
        // ── PSI 新增字段 ──────────────────────────────────────
        val cursorClassName: String?   = null,  // 光标所在类名（PSI 解析）
        val cursorMethodName: String?  = null   // 光标所在方法/函数名（PSI 解析）
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

        // PSI：光标所在类/方法
        val psiLocation = listOfNotNull(ctx.cursorClassName, ctx.cursorMethodName).joinToString(" > ")
        if (psiLocation.isNotBlank()) {
            sb.appendLine("▸ 光标位置: $psiLocation（第 ${ctx.cursorLine ?: "?"} 行）")
        } else if (ctx.cursorLine != null) {
            sb.appendLine("▸ 光标位置: 第 ${ctx.cursorLine} 行, 第 ${ctx.cursorColumn ?: 1} 列")
        }

        // 打开的文件列表（最多显示 5 个）
        if (ctx.openFiles.isNotEmpty()) {
            sb.appendLine("▸ 打开的文件: ${ctx.openFiles.take(5).joinToString(", ") { "`$it`" }}")
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

        // 7. PSI 解析：光标所在类名 / 方法名
        val (cursorClassName, cursorMethodName) = resolvePsiContext(project, editor)

        return EditorContext(
            activeFilePath = relativePath,
            activeFileName = virtualFile?.name,
            language = language,
            cursorLine = cursorLine,
            cursorColumn = cursorColumn,
            selectedCode = selectedCode,
            surroundingCode = surroundingCode,
            surroundingStartLine = startLine + 1,  // 1-based
            openFiles = openFiles,
            cursorClassName  = cursorClassName,
            cursorMethodName = cursorMethodName
        )
    }

    /**
     * 通过 PSI 解析光标所在的类名和方法/函数名
     *
     * 兼容策略：
     *  - 优先使用反射调用 Java/Kotlin PSI API（PsiClass、PsiMethod、KtClass、KtFunction）
     *  - PSI 不可用（非 Java/Kotlin 文件）时优雅降级，返回 null
     */
    private fun resolvePsiContext(project: Project, editor: Editor): Pair<String?, String?> {
        return try {
            val document = editor.document
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                ?: return null to null

            val offset = editor.caretModel.offset
            val element: PsiElement = psiFile.findElementAt(offset) ?: return null to null

            // 解析方法名（通用：通过反射检测 PSI 元素名称，兼容 Java/Kotlin/Go/Python 等）
            val methodName = findContainingMethodName(element)
            val className  = findContainingClassName(element)

            className to methodName
        } catch (e: Exception) {
            log.debug("PSI 解析失败（非致命）: ${e.message}")
            null to null
        }
    }

    /**
     * 向上查找光标所在的方法/函数名
     * 通过反射检测常见 PSI 接口，避免硬依赖 Java/Kotlin 插件
     */
    private fun findContainingMethodName(element: PsiElement): String? {
        var current: PsiElement? = element.parent
        while (current != null) {
            val name = tryGetNamedElementName(current, setOf(
                "com.intellij.psi.PsiMethod",
                "org.jetbrains.kotlin.psi.KtNamedFunction",
                "com.intellij.psi.PsiFunction",
                "com.goide.psi.GoFunctionDeclaration",
                "com.jetbrains.python.psi.PyFunction"
            ))
            if (name != null) return name
            current = current.parent
        }
        return null
    }

    /**
     * 向上查找光标所在的类/对象名
     */
    private fun findContainingClassName(element: PsiElement): String? {
        var current: PsiElement? = element.parent
        while (current != null) {
            val name = tryGetNamedElementName(current, setOf(
                "com.intellij.psi.PsiClass",
                "org.jetbrains.kotlin.psi.KtClass",
                "org.jetbrains.kotlin.psi.KtObjectDeclaration",
                "com.goide.psi.GoTypeSpec",
                "com.jetbrains.python.psi.PyClass"
            ))
            if (name != null) return name
            current = current.parent
        }
        return null
    }

    /**
     * 反射尝试获取 PSI 元素的 name（调用 getName() 方法）
     * 如果元素是指定类型之一且有名称，则返回；否则返回 null
     */
    private fun tryGetNamedElementName(element: PsiElement, targetClassNames: Set<String>): String? {
        for (className in targetClassNames) {
            try {
                val clazz = Class.forName(className)
                if (clazz.isInstance(element)) {
                    val nameMethod = clazz.getMethod("getName")
                    return nameMethod.invoke(element) as? String
                }
            } catch (_: ClassNotFoundException) {
                // 该语言插件未安装，跳过
            } catch (_: Exception) {
                // 其他反射异常，跳过
            }
        }
        return null
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

