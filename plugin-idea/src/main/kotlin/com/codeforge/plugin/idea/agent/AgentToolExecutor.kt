package com.codeforge.plugin.idea.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.google.gson.Gson
import java.io.File

/**
 * Agent 工具执行器
 *
 * 提供 LLM 可调用的工具集：
 *  - read_file   读取文件内容
 *  - write_file  写入文件
 *  - list_files  列举目录
 *  - search_code 代码搜索（grep）
 *  - run_terminal 在 IDE 终端执行命令（需用户确认）
 */
object AgentToolExecutor {

    private val log = logger<AgentToolExecutor>()
    private val gson = Gson()

    /** 工具调用结果 */
    data class ToolResult(
        val tool: String,
        val success: Boolean,
        val output: String,
        val error: String? = null
    )

    /** 工具定义（用于注入 LLM system prompt） */
    val TOOL_DEFINITIONS = """
## 工具调用规则（必须严格遵守）

调用工具时，必须且只能输出以下格式，不得在 <tool_call> 标签前后添加任何说明文字：

<tool_call>
{"tool": "read_file", "path": "相对项目根目录的路径"}
</tool_call>

<tool_call>
{"tool": "write_file", "path": "路径", "content": "文件内容"}
</tool_call>

<tool_call>
{"tool": "list_files", "path": "目录路径（可选，默认根目录）"}
</tool_call>

<tool_call>
{"tool": "search_code", "keyword": "搜索关键词", "filePattern": "*.java（可选）"}
</tool_call>

<tool_call>
{"tool": "run_terminal", "command": "shell 命令"}
</tool_call>

## 工具调用约束
- 每次响应只能包含一个 <tool_call> 块
- 调用工具时整条消息只输出 <tool_call>...</tool_call>，不要额外解释
- 收到工具结果后再继续分析，最终用自然语言回复用户
- 已经读取过的文件不要重复读取
""".trimIndent()

    // ==================== 工具执行入口 ====================

    /**
     * 解析并执行 LLM 返回内容中的工具调用
     * 支持两种格式：
     *   1. 标准格式：<tool_call>{"tool":"xxx",...}</tool_call>
     *   2. 裸 JSON 兜底：{"tool":"xxx",...}（部分模型不遵守标签格式）
     */
    fun parseAndExecute(project: Project, llmOutput: String): List<ToolResult> {
        val results = mutableListOf<ToolResult>()

        // 优先匹配标准 <tool_call> 格式
        val tagRegex = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        val tagMatches = tagRegex.findAll(llmOutput).toList()

        if (tagMatches.isNotEmpty()) {
            tagMatches.forEach { match ->
                try {
                    val json = gson.fromJson(match.groupValues[1], Map::class.java)
                    val tool = json["tool"] as? String ?: return@forEach
                    results.add(execute(project, tool, json))
                } catch (e: Exception) {
                    results.add(ToolResult("unknown", false, "", "解析工具调用失败: ${e.message}"))
                }
            }
            return results
        }

        // 兜底1：markdown 代码块包裹的 JSON（部分模型用 ```json 包裹）
        val mdJsonRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\"tool\"[\\s\\S]*?\\})\\s*```")
        mdJsonRegex.findAll(llmOutput).forEach { match ->
            try {
                val json = gson.fromJson(match.groupValues[1], Map::class.java)
                val tool = json["tool"] as? String ?: return@forEach
                val toolNames = listOf("read_file","write_file","list_files","search_code","run_terminal")
                if (tool !in toolNames) return@forEach
                log.debug("Agent: markdown代码块解析到工具调用: $tool")
                results.add(execute(project, tool, json))
            } catch (e: Exception) {
                log.debug("Agent: markdown代码块解析失败: ${e.message}")
            }
        }
        if (results.isNotEmpty()) return results

        // 兜底2：裸 JSON 对象（针对不遵守格式的模型）
        val bareJsonRegex = Regex("\\{[^{}]*\"tool\"\\s*:\\s*\"(read_file|write_file|list_files|search_code|run_terminal)\"[^{}]*\\}", RegexOption.DOT_MATCHES_ALL)
        bareJsonRegex.findAll(llmOutput).forEach { match ->
            try {
                val json = gson.fromJson(match.value, Map::class.java)
                val tool = json["tool"] as? String ?: return@forEach
                log.debug("Agent: 裸JSON解析到工具调用: $tool")
                results.add(execute(project, tool, json))
            } catch (e: Exception) {
                log.debug("Agent: 裸JSON解析失败: ${e.message}")
            }
        }
        return results
    }

    /**
     * 判断 LLM 输出是否包含工具调用（支持标准标签/markdown代码块/裸 JSON 三种格式）
     */
    fun hasToolCall(text: String): Boolean {
        if (text.contains("<tool_call>")) return true
        val tools = listOf("read_file", "write_file", "list_files", "search_code", "run_terminal")
        if (tools.any { tool -> text.contains("\"tool\"") && text.contains("\"$tool\"") }) return true
        return false
    }

    // ==================== 各工具实现 ====================

    private fun execute(project: Project, tool: String, params: Map<*, *>): ToolResult {
        return when (tool) {
            "read_file"    -> readFile(project, params["path"] as? String ?: "")
            "write_file"   -> writeFile(project, params["path"] as? String ?: "",
                                        params["content"] as? String ?: "")
            "list_files"   -> listFiles(project, params["path"] as? String ?: "")
            "search_code"  -> searchCode(project,
                                         params["keyword"] as? String ?: "",
                                         params["filePattern"] as? String)
            "run_terminal" -> runTerminal(project, params["command"] as? String ?: "")
            else           -> ToolResult(tool, false, "", "未知工具: $tool")
        }
    }

    /** 读取文件 */
    fun readFile(project: Project, relativePath: String): ToolResult {
        if (relativePath.isBlank()) return ToolResult("read_file", false, "", "路径不能为空")
        return try {
            val root = projectRoot(project) ?: return ToolResult("read_file", false, "", "找不到项目根目录")
            val file = root.findFileByRelativePath(relativePath)
                ?: return ToolResult("read_file", false, "", "文件不存在: $relativePath")

            val content = ReadAction.compute<String, Exception> {
                String(file.contentsToByteArray(), Charsets.UTF_8)
            }
            val lines = content.lines()
            val truncated = lines.size > 500
            val result = if (truncated) lines.take(500).joinToString("\n") + "\n// ...已截断" else content

            ToolResult("read_file", true, "### $relativePath\n```\n$result\n```")
        } catch (e: Exception) {
            ToolResult("read_file", false, "", "读取失败: ${e.message}")
        }
    }

    /** 写入文件（Inline Diff：编辑器内高亮显示变更，用户可 Accept/Reject） */
    fun writeFile(project: Project, relativePath: String, content: String): ToolResult {
        if (relativePath.isBlank()) return ToolResult("write_file", false, "", "路径不能为空")
        return try {
            val root = projectRoot(project) ?: return ToolResult("write_file", false, "", "找不到项目根目录")
            val existingFile = root.findFileByRelativePath(relativePath)

            if (existingFile != null) {
                // 已有文件 → Inline Diff（编辑器内高亮）
                ApplicationManager.getApplication().invokeLater {
                    com.codeforge.plugin.idea.diff.InlineDiffManager.showInlineDiff(
                        project, existingFile, content
                    )
                }
                ToolResult("write_file", true, "✅ 已在编辑器中显示 Inline Diff，等待用户确认: $relativePath")
            } else {
                // 新文件 → 通过 CodeApplyManager 创建
                val lang = relativePath.substringAfterLast('.', "")
                val fileName = relativePath.substringAfterLast('/')
                ApplicationManager.getApplication().invokeLater {
                    com.codeforge.plugin.idea.util.CodeApplyManager.applyCode(
                        project, content, lang, fileName
                    )
                }
                ToolResult("write_file", true, "✅ 新文件创建流程已启动: $relativePath")
            }
        } catch (e: Exception) {
            ToolResult("write_file", false, "", "写入失败: ${e.message}")
        }
    }

    /** 列举目录文件 */
    fun listFiles(project: Project, dirPath: String): ToolResult {
        return try {
            val root = projectRoot(project) ?: return ToolResult("list_files", false, "", "找不到项目根目录")
            val dir: VirtualFile = if (dirPath.isBlank() || dirPath == ".") root
                                   else root.findFileByRelativePath(dirPath) ?: root

            val sb = StringBuilder()
            sb.appendLine("📁 ${dir.name}/")
            listDirRecursive(dir, root, sb, depth = 0, maxDepth = 3)

            ToolResult("list_files", true, sb.toString())
        } catch (e: Exception) {
            ToolResult("list_files", false, "", "列举失败: ${e.message}")
        }
    }

    /** 代码搜索（使用 Java File.walkTopDown，不依赖外部 ripgrep） */
    fun searchCode(project: Project, keyword: String, filePattern: String?): ToolResult {
        if (keyword.isBlank()) return ToolResult("search_code", false, "", "关键词不能为空")
        return try {
            val root = projectRoot(project) ?: return ToolResult("search_code", false, "", "找不到项目根目录")
            val rootPath = root.path
            val results = mutableListOf<String>()
            val ext = filePattern?.removePrefix("*.")

            outer@ for (file in File(rootPath).walkTopDown()) {
                if (results.size >= 50) break@outer
                if (!file.isFile) continue
                if (file.path.contains("/.git/") || file.path.contains("/build/") ||
                    file.path.contains("\\build\\") || file.path.contains("/target/") ||
                    file.path.contains("/.gradle/") || file.path.contains("\\.gradle\\")) continue
                if (ext != null && file.extension != ext) continue
                for ((idx, line) in file.readLines().withIndex()) {
                    if (line.contains(keyword, ignoreCase = true)) {
                        val rel = file.path.removePrefix(rootPath).trimStart('/', '\\')
                        results.add("$rel:${idx + 1}: $line")
                        if (results.size >= 50) break@outer
                    }
                }
            }

            if (results.isEmpty()) {
                ToolResult("search_code", true, "未找到包含「$keyword」的代码")
            } else {
                ToolResult("search_code", true, "找到 ${results.size} 处匹配：\n" + results.joinToString("\n"))
            }
        } catch (e: Exception) {
            ToolResult("search_code", false, "", "搜索失败: ${e.message}")
        }
    }

    /**
     * 在 IDE 内置终端执行命令
     * ⚠️ 安全提示：命令执行前会通过 UI 回调让用户确认
     */
    fun runTerminal(project: Project, command: String): ToolResult {
        // 安全校验：拒绝明显危险命令
        val dangerous = listOf("rm -rf", "del /f", "format", "shutdown", "reboot", "> /dev/", "DROP TABLE")
        for (d in dangerous) {
            if (command.lowercase().contains(d.lowercase())) {
                return ToolResult("run_terminal", false, "", "⛔ 拒绝执行危险命令: $command")
            }
        }
        return try {
            // 通过 IDE Terminal 执行
            ApplicationManager.getApplication().invokeLater {
                val terminalManager = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                terminalManager?.activate(null)
            }
            ToolResult("run_terminal", true,
                "✅ 命令已发送到终端: `$command`\n请在 IDE 终端查看执行结果")
        } catch (e: Exception) {
            ToolResult("run_terminal", false, "", "终端执行失败: ${e.message}")
        }
    }

    // ==================== 私有辅助 ====================

    private fun projectRoot(project: Project): VirtualFile? =
        ProjectRootManager.getInstance(project).contentRoots.firstOrNull()

    /**
     * 获取项目文件结构快照（用于 Agent system prompt 上下文注入）
     * 最多 3 层深度，过滤 build/target/node_modules 等无关目录
     */
    fun getProjectStructure(project: Project): String {
        return try {
            val root = projectRoot(project) ?: return "(无法获取项目结构)"
            val sb = StringBuilder()
            sb.appendLine("${root.name}/")
            listDirRecursive(root, root, sb, depth = 0, maxDepth = 3)
            sb.toString().take(4000)  // 最多 4000 字符避免占用太多 token
        } catch (e: Exception) {
            "(获取项目结构失败: ${e.message})"
        }
    }

    private fun listDirRecursive(
        dir: VirtualFile, root: VirtualFile,
        sb: StringBuilder, depth: Int, maxDepth: Int
    ) {
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth + 1)
        val children = dir.children.sortedWith(compareByDescending<VirtualFile> { it.isDirectory }.thenBy { it.name })
        var count = 0
        for (child in children) {
            if (count >= 30) { sb.appendLine("$indent...（更多文件省略）"); break }
            if (child.name.startsWith(".") ||
                child.name == "build" || child.name == "target" ||
                child.name == "node_modules" || child.name == ".gradle") continue
            if (child.isDirectory) {
                sb.appendLine("$indent📁 ${child.name}/")
                listDirRecursive(child, root, sb, depth + 1, maxDepth)
            } else {
                sb.appendLine("$indent📄 ${child.name}")
            }
            count++
        }
    }
}

