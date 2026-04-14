package com.forgecode.plugin.idea.review

import com.forgecode.plugin.idea.service.LlmService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Review 服务：获取 Git Diff 并驱动 AI 进行代码审查
 */
object ReviewService {

    private val log = logger<ReviewService>()

    data class ChangedFile(
        val path: String,           // 文件相对路径
        val status: String,         // M=修改 A=新增 D=删除 R=重命名
        val additions: Int,         // 新增行数
        val deletions: Int          // 删除行数
    )

    data class ReviewResult(
        val filePath: String,
        val issues: List<ReviewIssue>
    )

    data class ReviewIssue(
        val severity: String,       // error / warning / info
        val line: Int?,             // 行号（可选）
        val message: String,        // 问题描述
        val suggestion: String?     // 改进建议
    )

    /**
     * 获取当前 Git 工作区的变更文件列表
     * 先取 staged（--cached），没有则取 unstaged
     */
    fun getChangedFiles(project: Project): List<ChangedFile> {
        val rootPath = project.basePath ?: return emptyList()
        return try {
            // 先取 staged diff
            var files = runGitDiffStat(rootPath, staged = true)
            if (files.isEmpty()) {
                // 没有 staged，取全部未提交变更
                files = runGitDiffStat(rootPath, staged = false)
            }
            files
        } catch (e: Exception) {
            log.warn("获取 Git 变更文件列表失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取指定文件列表的完整 diff 内容（自动合并 staged + unstaged）
     */
    fun getDiff(project: Project, filePaths: List<String>, staged: Boolean): String {
        val rootPath = project.basePath ?: return ""
        return try {
            val builder: (Boolean) -> String = { s ->
                val args = mutableListOf("git", "diff")
                if (s) args.add("--cached")
                args.add("--")
                args.addAll(filePaths)
                runCommand(rootPath, args)
            }
            if (staged) {
                // 先取 staged，没有再补 unstaged
                val stagedDiff = builder(true)
                val unstagedDiff = builder(false)
                listOf(stagedDiff, unstagedDiff).filter { it.isNotBlank() }.joinToString("\n")
            } else {
                // 先取 unstaged，没有再取 staged
                val unstagedDiff = builder(false)
                val stagedDiff = builder(true)
                listOf(unstagedDiff, stagedDiff).filter { it.isNotBlank() }.joinToString("\n")
            }
        } catch (e: Exception) {
            log.warn("获取 Git diff 失败: ${e.message}")
            ""
        }
    }

    /**
     * 执行 AI 代码审查
     * @param diff       git diff 内容
     * @param onToken    流式 token 回调
     * @param onDone     完成回调
     * @param onError    错误回调
     */
    fun reviewCode(
        diff: String,
        projectName: String,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (diff.isBlank()) {
            onError(Exception("没有检测到代码变更，请确认是否有 staged 或未提交的修改"))
            return
        }

        val systemPrompt = """你是一个专业的代码审查专家。请对以下 Git diff 进行详细的代码审查。

审查维度：
1. **代码质量**：命名规范、代码结构、可读性
2. **潜在问题**：空指针、边界条件、异常处理
3. **安全风险**：SQL注入、XSS、敏感信息泄露等
4. **性能问题**：不必要的循环、资源泄露、低效算法
5. **最佳实践**：是否符合语言/框架规范

输出格式要求：
- 按文件分组，每个文件单独一个段落
- 每个问题标明严重程度：🔴 严重 / 🟡 警告 / 🟢 建议
- 如有行号请标注
- 给出具体的改进建议代码（如果有）
- 如果代码质量良好，直接说明"""

        val userMessage = """项目：$projectName

以下是本次代码变更（git diff）：

```diff
${diff.take(12000)}${if (diff.length > 12000) "\n\n... (diff 过长，已截取前 12000 字符)" else ""}
```

请进行代码审查。"""

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userMessage)
        )

        LlmService.getInstance().chatStream(
            messages = messages,
            onToken = onToken,
            onDone = onDone,
            onError = onError
        )
    }

    // ==================== 私有工具方法 ====================

    private fun runGitDiffStat(rootPath: String, staged: Boolean): List<ChangedFile> {
        val args = mutableListOf("git", "diff", "--numstat")
        if (staged) args.add("--cached")
        val output = runCommand(rootPath, args)
        if (output.isBlank()) return emptyList()

        return output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                // numstat 格式: "additions\tdeletions\tfilepath"
                val parts = line.split("\t")
                if (parts.size < 3) return@mapNotNull null
                val additions = parts[0].toIntOrNull() ?: 0
                val deletions = parts[1].toIntOrNull() ?: 0
                val path = parts[2].trim()
                // 状态：有删除行认为是修改，全新文件删除=0
                val status = when {
                    additions > 0 && deletions == 0 -> "A"
                    additions == 0 && deletions > 0 -> "D"
                    else -> "M"
                }
                ChangedFile(path, status, additions, deletions)
            }
    }

    private fun runCommand(workDir: String, args: List<String>): String {
        val process = ProcessBuilder(args)
            .directory(java.io.File(workDir))
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            .readText()
        process.waitFor()
        return output.trim()
    }
}
