package com.codeforge.plugin.idea.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit

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

    /**
     * 当前 Agent 任务 ID（线程局部变量）
     * AgentService.runAgent() 开始时设置，结束时清除。
     * writeFile() 读取此 ID 决定是否进入"收集模式"（多文件队列）。
     */
    var currentTaskId: String? = null

    /**
     * P2-9：当前 Checkpoint ID（与 currentTaskId 同生命周期）
     * writeFile() 调用时先调 CheckpointManager.recordSnapshot 保存原始内容。
     */
    var currentCheckpointId: String? = null

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
        // A5：检查工具配置，DISABLED 则直接拒绝执行
        val toolMode = com.codeforge.plugin.idea.settings.CodeForgeSettings.getInstance().getToolMode(tool)
        if (toolMode == com.codeforge.plugin.idea.settings.CodeForgeSettings.ToolMode.DISABLED) {
            log.info("AgentToolExecutor: 工具 '$tool' 已被禁用（工具配置：关闭）")
            return ToolResult(tool, false, "", "工具 '$tool' 已关闭，请在工具配置面板（/tools）中开启")
        }

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

    /**
     * 写入文件
     *
     * 两种模式：
     *  - Agent 模式（currentTaskId != null）：收集到多文件队列，任务完成后统一展示确认面板
     *  - 手动/单次模式（currentTaskId == null）：立即展示 Inline Diff 或创建新文件（原有行为）
     */
    fun writeFile(project: Project, relativePath: String, content: String): ToolResult {
        if (relativePath.isBlank()) return ToolResult("write_file", false, "", "路径不能为空")
        return try {
            val root = projectRoot(project) ?: return ToolResult("write_file", false, "", "找不到项目根目录")
            val existingFile = root.findFileByRelativePath(relativePath)
            val taskId = currentTaskId

            if (taskId != null) {
                // ── Agent 多文件队列模式：收集 patch，不弹窗 ────────────────
                val originalContent = if (existingFile != null) {
                    ReadAction.compute<String, Exception> {
                        String(existingFile.contentsToByteArray(), Charsets.UTF_8)
                    }
                } else ""

                // P2-9：在写入前，将原始内容记录到 CheckpointManager
                val cpId = currentCheckpointId
                if (cpId != null) {
                    CheckpointManager.recordSnapshot(
                        checkpointId  = cpId,
                        relativePath  = relativePath,
                        originalContent = if (existingFile != null) originalContent else null
                    )
                }

                val patch = com.codeforge.plugin.idea.diff.MultiFileDiffManager.FilePatch(
                    relativePath    = relativePath,
                    originalContent = originalContent,
                    newContent      = content,
                    isNewFile       = (existingFile == null)
                )
                com.codeforge.plugin.idea.diff.MultiFileDiffManager.addPatch(taskId, patch)

                log.info("writeFile [队列模式]: 收集 patch [$relativePath]（task=$taskId）")
                ToolResult("write_file", true,
                    "✅ 已将 [$relativePath] 加入变更队列，Agent 任务完成后将统一展示确认面板")

            } else if (existingFile != null) {
                // ── 手动模式（已有文件）：立即显示 Inline Diff ───────────────
                ApplicationManager.getApplication().invokeLater {
                    com.codeforge.plugin.idea.diff.InlineDiffManager.showInlineDiff(
                        project, existingFile, content
                    )
                }
                ToolResult("write_file", true, "✅ 已在编辑器中显示 Inline Diff，等待用户确认: $relativePath")

            } else {
                // ── 手动模式（新文件）：CodeApplyManager 创建 ─────────────────
                val lang     = relativePath.substringAfterLast('.', "")
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

    /**
     * T23：代码搜索增强版
     *
     * 优先调用 CodebaseSearcher（PSI 类名/方法名语义搜索）；
     * 若关键词看起来是普通字符串搜索（含空格或特殊符号），则直接走 grep。
     */
    fun searchCode(project: Project, keyword: String, filePattern: String?): ToolResult {
        if (keyword.isBlank()) return ToolResult("search_code", false, "", "关键词不能为空")
        return try {
            // 判断是否适合走 PSI 语义搜索（单词、无空格、无通配符）
            val isPsiCandidate = filePattern == null &&
                keyword.length >= 2 &&
                !keyword.contains(' ') &&
                !keyword.contains('*') &&
                !keyword.contains('"')

            if (isPsiCandidate) {
                // 优先 PSI 语义搜索
                val psiResults = com.codeforge.plugin.idea.util.CodebaseSearcher
                    .searchByName(project, keyword, limit = 40)
                if (psiResults.isNotEmpty()) {
                    val text = com.codeforge.plugin.idea.util.CodebaseSearcher.formatResults(psiResults)
                    return ToolResult("search_code", true, "找到 ${psiResults.size} 处匹配（PSI 语义搜索）：\n$text")
                }
            }

            // 降级：grep 文件内容逐行搜索
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
     * 真实执行 shell 命令并捕获输出，返回给 LLM 作为工具结果。
     *
     * 执行流程：
     *  1. 危险命令黑名单校验（直接拒绝，不弹框）
     *  2. 弹出用户确认对话框（EDT，阻塞等待用户决定）
     *  3. ProcessBuilder 真实执行命令（工作目录 = 项目根目录）
     *  4. 捕获 stdout + stderr（合并流），最多 60 秒 / 8000 字符
     *  5. 将完整输出封装为 ToolResult 返回给 LLM
     *
     * 安全机制：
     *  - 黑名单：rm -rf /、del /f /s、format c:、shutdown 等
     *  - 用户确认：每次执行前弹窗（除非设置中关闭）
     *  - 输出截断：超过 8000 字符时截断并提示
     *  - 超时保护：60 秒后强制终止进程
     */
    fun runTerminal(project: Project, command: String): ToolResult {
        if (command.isBlank()) return ToolResult("run_terminal", false, "", "命令不能为空")

        // ── 1. 危险命令黑名单（直接拒绝，不弹确认框）──────────────────────
        val dangerousPatterns = listOf(
            "rm -rf /", "rm -rf ~",
            "del /f /s /q c:\\", "del /f /s /q d:\\",
            "format c:", "format d:",
            "shutdown /s", "shutdown -s",
            "reboot", "halt",
            ":(){:|:&};:",          // fork bomb
            "> /dev/sda", "> /dev/sdb"
        )
        for (pattern in dangerousPatterns) {
            if (command.lowercase().contains(pattern.lowercase())) {
                log.warn("Agent runTerminal: 拒绝危险命令 [$command]")
                return ToolResult("run_terminal", false, "",
                    "⛔ 拒绝执行危险命令（命中黑名单规则「$pattern」）")
            }
        }

        // ── 2. 用户确认对话框（EDT 阻塞）────────────────────────────────────
        var userConfirmed = false
        ApplicationManager.getApplication().invokeAndWait {
            val result = Messages.showYesNoDialog(
                project,
                "Agent 请求执行以下终端命令：\n\n    ${command}\n\n是否允许执行？",
                "CodeForge — 命令执行确认",
                "允许执行",
                "拒绝",
                Messages.getWarningIcon()
            )
            userConfirmed = (result == Messages.YES)
        }
        if (!userConfirmed) {
            log.info("Agent runTerminal: 用户拒绝执行命令 [$command]")
            return ToolResult("run_terminal", false, "", "用户拒绝执行该命令")
        }

        // ── 3. 真实执行 ───────────────────────────────────────────────────────
        log.info("Agent runTerminal: 开始执行命令 [$command]")
        return try {
            val workDir = project.basePath?.let { File(it) }
                ?: File(System.getProperty("user.home"))

            val isWindows = System.getProperty("os.name")
                .lowercase().contains("win")
            val processCmd = if (isWindows) listOf("cmd.exe", "/c", command)
                             else           listOf("bash", "-c", command)

            val process = ProcessBuilder(processCmd)
                .directory(workDir)
                .redirectErrorStream(true)   // stderr 合并到 stdout
                .start()

            // ── 4. 捕获输出（最多 60s / 8000 字符）─────────────────────────
            val outputBuilder = StringBuilder()
            val deadline = System.currentTimeMillis() + 60_000L
            var truncated = false

            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (System.currentTimeMillis() < deadline) {
                    val line = reader.readLine() ?: break
                    outputBuilder.appendLine(line)
                    if (outputBuilder.length > 8000) {
                        truncated = true
                        break
                    }
                }
            }

            // 等待进程结束（最多再等 5 秒）
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()

            val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
            val rawOutput = outputBuilder.toString().trimEnd()
            val output = if (truncated) "$rawOutput\n\n... (输出超过 8000 字符，已截断)" else rawOutput
            val displayOutput = output.ifBlank { "(命令无输出)" }

            log.info("Agent runTerminal: 命令执行完毕，exit=$exitCode，输出 ${output.length} 字符")

            if (exitCode == 0) {
                ToolResult(
                    tool    = "run_terminal",
                    success = true,
                    output  = "✅ 命令执行成功（exit code: 0）\n\n```\n\$ $command\n$displayOutput\n```"
                )
            } else {
                ToolResult(
                    tool    = "run_terminal",
                    success = false,
                    output  = "❌ 命令执行失败（exit code: $exitCode）\n\n```\n\$ $command\n$displayOutput\n```",
                    error   = "exit code: $exitCode"
                )
            }
        } catch (e: Exception) {
            log.error("Agent runTerminal: 执行异常", e)
            ToolResult("run_terminal", false, "", "命令执行异常: ${e.message}")
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

