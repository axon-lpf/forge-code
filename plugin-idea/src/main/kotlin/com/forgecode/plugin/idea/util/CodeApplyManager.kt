package com.forgecode.plugin.idea.util

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.roots.ProjectRootManager

/**
 * 代码 Apply 管理器
 *
 * 功能：
 *  1. 弹出 Diff 视图让用户对比原始代码与 AI 生成代码
 *  2. 用户接受后写入目标文件
 *  3. 支持新建文件
 */
object CodeApplyManager {

    private val log = logger<CodeApplyManager>()

    /**
     * Apply 入口 — 智能选择目标文件并弹出 Diff 视图
     *
     * @param project   当前项目
     * @param newCode   AI 生成的新代码
     * @param language  代码语言（kotlin/java/python...）
     * @param fileName  可选的目标文件名（AI 可能提供）
     */
    fun applyCode(
        project: Project,
        newCode: String,
        language: String,
        fileName: String? = null
    ) {
        ApplicationManager.getApplication().invokeLater {
            // 1. 确定目标文件
            val targetFile = resolveTargetFile(project, fileName, language)

            if (targetFile == null) {
                // 没有目标文件 → 新建文件
                applyAsNewFile(project, newCode, language, fileName)
            } else {
                // 有目标文件 → 弹 Diff 视图
                showDiffAndApply(project, targetFile, newCode)
            }
        }
    }

    /**
     * 直接写入当前编辑器（不弹 Diff，快速 apply）
     */
    fun applyToCurrentEditor(project: Project, newCode: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            applyCode(project, newCode, "")
            return
        }

        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
        if (virtualFile != null) {
            showDiffAndApply(project, virtualFile, newCode)
        } else {
            WriteCommandAction.runWriteCommandAction(project, Runnable {
                val doc = editor.document
                val sel = editor.selectionModel
                if (sel.hasSelection()) {
                    doc.replaceString(sel.selectionStart, sel.selectionEnd, newCode)
                } else {
                    doc.setText(newCode)
                }
            })
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 弹出 Diff 视图，让用户对比后决定是否写入
     */
    private fun showDiffAndApply(project: Project, targetFile: VirtualFile, newCode: String) {
        val factory = DiffContentFactory.getInstance()

        // 左侧：现有文件内容
        val originalContent = factory.create(project, targetFile)

        // 右侧：AI 生成内容（用文本内容 + 文件类型）
        val newContent = factory.create(newCode, targetFile.fileType)

        val request = SimpleDiffRequest(
            "Forge Code — Apply 代码变更",
            originalContent,
            newContent,
            "原始文件: ${targetFile.name}",
            "AI 生成代码"
        )

        DiffManager.getInstance().showDiff(project, request)

        // Diff 视图是非模态的，需要提供"接受变更"的方式
        // 使用对话框询问是否写入（简单可靠）
        ApplicationManager.getApplication().invokeLater {
            val choice = Messages.showYesNoDialog(
                project,
                "是否将 AI 生成的代码写入 ${targetFile.name}？",
                "Forge Code — 确认应用变更",
                "✅ 写入文件",
                "❌ 取消",
                Messages.getQuestionIcon()
            )
            if (choice == Messages.YES) {
                writeToFile(project, targetFile, newCode)
            }
        }
    }

    /**
     * 写入文件
     */
    private fun writeToFile(project: Project, virtualFile: VirtualFile, content: String) {
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            try {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                if (document != null) {
                    document.setText(content)
                    FileDocumentManager.getInstance().saveDocument(document)
                } else {
                    VfsUtil.saveText(virtualFile, content)
                }
                log.info("代码已写入: ${virtualFile.path}")
            } catch (e: Exception) {
                log.error("写入文件失败: ${virtualFile.path}", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "写入失败：${e.message}", "Forge Code")
                }
            }
        })
    }

    /**
     * 新建文件并写入代码
     */
    private fun applyAsNewFile(
        project: Project,
        code: String,
        language: String,
        suggestedName: String?
    ) {
        val ext = languageToExtension(language)
        val defaultName = suggestedName ?: "forge_generated.$ext"

        // 弹出输入框让用户确认文件名
        val inputName = Messages.showInputDialog(
            project,
            "请输入新文件名：",
            "Forge Code — 新建文件",
            Messages.getQuestionIcon(),
            defaultName,
            null
        ) ?: return

        // 确定存储目录（优先 src/main/java 或项目根）
        val baseDir = findBestDirectory(project)
        if (baseDir == null) {
            Messages.showErrorDialog(project, "无法确定目标目录", "Forge Code")
            return
        }

        WriteCommandAction.runWriteCommandAction(project, Runnable {
            try {
                val newFile = baseDir.createChildData(this, inputName)
                VfsUtil.saveText(newFile, code)
                // 在编辑器中打开新文件
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(newFile, true)
                }
                log.info("新文件已创建: ${newFile.path}")
            } catch (e: Exception) {
                log.error("创建文件失败", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "创建失败：${e.message}", "Forge Code")
                }
            }
        })
    }

    /**
     * 智能确定目标文件：
     * 1. 若 fileName 提供且在项目中存在 → 直接用
     * 2. 否则用当前打开的编辑器文件
     */
    private fun resolveTargetFile(
        project: Project,
        fileName: String?,
        language: String
    ): VirtualFile? {
        // 优先：当前活跃编辑器
        val activeFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        // 若 AI 提供了文件名，在项目中查找
        if (!fileName.isNullOrBlank()) {
            val found = findFileInProject(project, fileName)
            if (found != null) return found
        }

        // 语言匹配：当前文件是否与代码语言一致
        if (activeFile != null) {
            val ext = activeFile.extension?.lowercase() ?: ""
            val langExt = languageToExtension(language)
            if (ext == langExt || language.isBlank()) return activeFile
        }

        return activeFile
    }

    /**
     * 在项目中按文件名查找
     */
    private fun findFileInProject(project: Project, fileName: String): VirtualFile? {
        val roots = ProjectRootManager.getInstance(project).contentRoots
        for (root in roots) {
            val found = findRecursive(root, fileName)
            if (found != null) return found
        }
        return null
    }

    private fun findRecursive(dir: VirtualFile, name: String): VirtualFile? {
        if (!dir.isDirectory) return null
        for (child in dir.children) {
            if (!child.isDirectory && child.name.equals(name, ignoreCase = true)) return child
            if (child.isDirectory) {
                val found = findRecursive(child, name)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * 找最佳存放新文件的目录
     */
    private fun findBestDirectory(project: Project): VirtualFile? {
        val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        if (sourceRoots.isNotEmpty()) return sourceRoots.first()
        return ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
    }

    private fun languageToExtension(language: String): String = when (language.lowercase()) {
        "java"              -> "java"
        "kotlin"            -> "kt"
        "python"            -> "py"
        "javascript", "js"  -> "js"
        "typescript", "ts"  -> "ts"
        "go"                -> "go"
        "rust"              -> "rs"
        "cpp", "c++"        -> "cpp"
        "c"                 -> "c"
        "swift"             -> "swift"
        "xml"               -> "xml"
        "yaml", "yml"       -> "yaml"
        "json"              -> "json"
        "html"              -> "html"
        "css"               -> "css"
        "sql"               -> "sql"
        "bash", "sh"        -> "sh"
        "markdown", "md"    -> "md"
        else                -> "txt"
    }
}
