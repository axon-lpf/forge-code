package com.forgecode.plugin.idea.service

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.roots.ProjectRootManager
import java.io.IOException

/**
 * 文件上下文提供者 — 为 @文件引用 功能提供：
 *  1. 项目文件搜索（模糊匹配文件名）
 *  2. 文件内容读取
 *  3. Token 预估与截断
 */
object FileContextProvider {

    private val log = logger<FileContextProvider>()

    /** 单文件最大读取行数（防止超大文件塞爆 context） */
    private const val MAX_LINES = 500
    /** 单文件最大字符数 */
    private const val MAX_CHARS = 20_000

    /** 搜索结果条目 */
    data class FileEntry(
        val path: String,       // 相对项目根目录的路径，如 src/main/java/UserService.java
        val name: String,       // 文件名，如 UserService.java
        val size: Long,         // 文件大小（字节）
        val language: String    // 语言标识，如 java / kotlin / python
    )

    /** 读取结果 */
    data class FileContent(
        val path: String,
        val content: String,
        val truncated: Boolean,  // 是否因超限被截断
        val totalLines: Int
    )

    // ==================== 公开 API ====================

    /**
     * 在项目中模糊搜索文件
     * @param keyword 搜索关键词（匹配文件名）
     * @param limit   最多返回条数
     */
    fun searchFiles(project: Project, keyword: String, limit: Int = 20): List<FileEntry> {
        if (keyword.isBlank()) return recentFiles(project, limit)

        val kw = keyword.lowercase().trim()
        val results = mutableListOf<FileEntry>()

        try {
            val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots
            val roots: List<VirtualFile> = if (sourceRoots.isNotEmpty()) {
                sourceRoots.toList()
            } else {
                ProjectRootManager.getInstance(project).contentRoots.toList()
            }

            for (root in roots) {
                VfsUtil.iterateChildrenRecursively(root, null) { file ->
                    if (!file.isDirectory && matchFile(file, kw)) {
                        results.add(toEntry(file, root))
                    }
                    results.size < limit * 3
                }
                if (results.size >= limit * 3) break
            }
        } catch (e: Exception) {
            log.warn("文件搜索失败: $keyword", e)
        }

        // 按相关度排序：完全匹配文件名 > 前缀匹配 > 包含匹配
        return results
            .sortedWith(compareByDescending<FileEntry> { it.name.lowercase() == kw }
                .thenByDescending { it.name.lowercase().startsWith(kw) }
                .thenBy { it.path })
            .take(limit)
    }

    /**
     * 读取文件内容，超长时截断
     */
    fun readFile(project: Project, relativePath: String): FileContent? {
        return try {
            val root = findProjectRoot(project) ?: return null
            val file = root.findFileByRelativePath(relativePath) ?: return null
            if (file.isDirectory) return null

            val text = String(file.contentsToByteArray(), Charsets.UTF_8)
            val lines = text.lines()
            val totalLines = lines.size

            val truncated = text.length > MAX_CHARS || totalLines > MAX_LINES
            val content = if (truncated) {
                lines.take(MAX_LINES).joinToString("\n").take(MAX_CHARS) +
                        "\n\n// ... 文件过长，已截断（共 $totalLines 行）"
            } else {
                text
            }

            FileContent(
                path = relativePath,
                content = content,
                truncated = truncated,
                totalLines = totalLines
            )
        } catch (e: IOException) {
            log.warn("读取文件失败: $relativePath", e)
            null
        }
    }

    /**
     * 将多个 @引用文件 拼接为 system 提示词注入到消息前
     */
    fun buildContextBlock(files: List<FileContent>): String {
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("以下是用户引用的项目文件内容，请结合这些文件来回答问题：\n")
        files.forEach { f ->
            val lang = detectLang(f.path)
            sb.appendLine("### 文件: `${f.path}`${if (f.truncated) "（已截断）" else ""}")
            sb.appendLine("```$lang")
            sb.appendLine(f.content)
            sb.appendLine("```\n")
        }
        return sb.toString()
    }

    // ==================== 私有方法 ====================

    private fun matchFile(file: VirtualFile, keyword: String): Boolean {
        val name = file.name.lowercase()
        // 过滤常见无用文件
        if (name.endsWith(".class") || name.endsWith(".jar") ||
            file.path.contains("/.git/") || file.path.contains("/build/") ||
            file.path.contains("/target/") || file.path.contains("/node_modules/") ||
            file.path.contains("/.gradle/")) return false
        return name.contains(keyword)
    }

    private fun toEntry(file: VirtualFile, root: VirtualFile): FileEntry {
        val relativePath = VfsUtil.getRelativePath(file, root) ?: file.name
        return FileEntry(
            path = relativePath,
            name = file.name,
            size = file.length,
            language = detectLang(file.name)
        )
    }

    private fun recentFiles(project: Project, limit: Int): List<FileEntry> {
        // 无关键词时返回项目根下常见入口文件
        val results = mutableListOf<FileEntry>()
        try {
            val roots = ProjectRootManager.getInstance(project).contentRoots
            for (root in roots) {
                root.children
                    .filter { !it.isDirectory }
                    .take(limit)
                    .forEach { results.add(toEntry(it, root)) }
            }
        } catch (_: Exception) {}
        return results.take(limit)
    }

    private fun findProjectRoot(project: Project): VirtualFile? {
        return ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
    }

    private fun detectLang(fileName: String): String {
        return when {
            fileName.endsWith(".kt")   -> "kotlin"
            fileName.endsWith(".java") -> "java"
            fileName.endsWith(".py")   -> "python"
            fileName.endsWith(".js")   -> "javascript"
            fileName.endsWith(".ts")   -> "typescript"
            fileName.endsWith(".go")   -> "go"
            fileName.endsWith(".rs")   -> "rust"
            fileName.endsWith(".cpp") || fileName.endsWith(".cc") -> "cpp"
            fileName.endsWith(".c")    -> "c"
            fileName.endsWith(".xml")  -> "xml"
            fileName.endsWith(".yaml") || fileName.endsWith(".yml") -> "yaml"
            fileName.endsWith(".json") -> "json"
            fileName.endsWith(".md")   -> "markdown"
            fileName.endsWith(".sql")  -> "sql"
            fileName.endsWith(".sh")   -> "bash"
            fileName.endsWith(".html") -> "html"
            fileName.endsWith(".css")  -> "css"
            else -> ""
        }
    }
}
