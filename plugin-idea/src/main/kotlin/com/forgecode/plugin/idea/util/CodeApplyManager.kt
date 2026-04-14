package com.forgecode.plugin.idea.util

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 代码 Apply 管理器
 *
 * 功能：
 *  1. 弹出模态 Diff 对话框，让用户预览原始代码与 AI 生成代码的差异
 *  2. 用户点击「应用代码」后写入目标文件
 *  3. 支持新建文件
 */
object CodeApplyManager {

    private val log = logger<CodeApplyManager>()

    /**
     * Apply 入口 — 智能选择目标文件并弹出 Diff 对话框
     */
    fun applyCode(
        project: Project,
        newCode: String,
        language: String,
        fileName: String? = null
    ) {
        ApplicationManager.getApplication().invokeLater {
            val targetFile = resolveTargetFile(project, fileName, language)
            if (targetFile == null) {
                applyAsNewFile(project, newCode, language, fileName)
            } else {
                showDiffAndApply(project, targetFile, newCode)
            }
        }
    }

    /**
     * 直接写入当前编辑器（带 Diff 预览）
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

    // ==================== 内部：模态 Diff 对话框 ====================

    /**
     * 弹出模态 Diff 对话框（DialogWrapper 内嵌 DiffRequestPanel）
     * 用户点击「应用代码」后才写入文件
     */
    private fun showDiffAndApply(project: Project, targetFile: VirtualFile, newCode: String) {
        val dialog = CodeDiffDialog(project, targetFile, newCode)
        if (dialog.showAndGet()) {
            writeToFile(project, targetFile, newCode)
        }
    }

    /**
     * 模态 Diff 对话框
     */
    private class CodeDiffDialog(
        private val project: Project,
        private val targetFile: VirtualFile,
        private val newCode: String
    ) : DialogWrapper(project, true) {

        private var diffPanel: DiffRequestPanel? = null

        init {
            title = "Forge Code — 代码变更预览：${targetFile.name}"
            setOKButtonText("✅ 应用代码")
            setCancelButtonText("✗ 取消")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val factory = DiffContentFactory.getInstance()
            val originalContent = factory.create(project, targetFile)
            val newContent = factory.create(newCode, targetFile.fileType)

            val request = SimpleDiffRequest(
                "Forge Code — 代码变更预览",
                originalContent,
                newContent,
                "原始文件：${targetFile.name}",
                "AI 生成代码"
            )

            // 创建可嵌入的 DiffRequestPanel，绑定生命周期到对话框
            val parentDisposable: Disposable = disposable
            val panel = DiffManager.getInstance().createRequestPanel(project, parentDisposable, null)
            panel.setRequest(request)
            diffPanel = panel

            val wrapper = JPanel(BorderLayout())
            wrapper.preferredSize = Dimension(920, 620)
            wrapper.add(panel.component, BorderLayout.CENTER)
            return wrapper
        }

        override fun dispose() {
            diffPanel = null
            super.dispose()
        }
    }

    // ==================== 私有工具方法 ====================

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

    private fun applyAsNewFile(
        project: Project,
        code: String,
        language: String,
        suggestedName: String?
    ) {
        val ext = languageToExtension(language)
        val defaultName = suggestedName ?: "forge_generated.$ext"

        val inputName = Messages.showInputDialog(
            project,
            "请输入新文件名：",
            "Forge Code — 新建文件",
            Messages.getQuestionIcon(),
            defaultName,
            null
        ) ?: return

        val baseDir = findBestDirectory(project)
        if (baseDir == null) {
            Messages.showErrorDialog(project, "无法确定目标目录", "Forge Code")
            return
        }

        WriteCommandAction.runWriteCommandAction(project, Runnable {
            try {
                val newFile = baseDir.createChildData(this, inputName)
                VfsUtil.saveText(newFile, code)
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

    private fun resolveTargetFile(
        project: Project,
        fileName: String?,
        language: String
    ): VirtualFile? {
        val activeFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        if (!fileName.isNullOrBlank()) {
            val found = findFileInProject(project, fileName)
            if (found != null) return found
        }

        if (activeFile != null) {
            val ext = activeFile.extension?.lowercase() ?: ""
            val langExt = languageToExtension(language)
            if (ext == langExt || language.isBlank()) return activeFile
        }

        return activeFile
    }

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