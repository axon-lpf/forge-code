package com.codeforge.plugin.idea.agent

import com.codeforge.plugin.idea.diff.MultiFileDiffManager
import com.codeforge.plugin.idea.service.LlmService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 服务 — 支持两种模式：
 *
 * 1. Agent 模式：LLM 可调用工具（read_file/write_file/search_code 等），
 *    自动多轮循环直到任务完成（无工具调用为止）
 *
 * 2. Spec 模式：先生成执行计划，用户确认后逐步执行
 */
object AgentService {

    private val log = logger<AgentService>()

    /** 全局取消标志，调用 cancel() 后当前 Agent 循环会在下一轮检查时退出 */
    private val cancelFlag = AtomicBoolean(false)

    /** B3：需求澄清答案（JS Bridge 设置） */
    @Volatile var pendingClarifyAnswer: String? = null

    fun cancel() {
        cancelFlag.set(true)
        log.info("AgentService: 收到取消指令")
    }

    /** Agent 执行状态 */
    data class AgentStep(
        val type: StepType,
        val content: String,
        val toolName: String? = null,
        val toolResult: String? = null,
        val toolInput: String? = null   // T10：工具调用的参数摘要（用于 UI 卡片展示）
    )

    enum class StepType {
        THINKING,       // AI 思考中
        TOOL_CALL,      // 调用工具
        TOOL_RESULT,    // 工具结果
        RESPONSE,       // 最终回复
        PLAN,           // Spec 模式：执行计划
        PLAN_STEP,      // Spec 模式：计划步骤
        ERROR
    }

    /** Spec 计划步骤 */
    data class PlanStep(
        val index: Int,
        val title: String,
        val description: String,
        var status: StepStatus = StepStatus.PENDING
    )

    enum class StepStatus { PENDING, RUNNING, DONE, ERROR }

    // ==================== Agent 模式 ====================

    /**
     * Agent 模式执行
     * LLM 可以调用工具，自动多轮循环
     *
     * @param project            当前项目
     * @param userMessage        用户输入
     * @param sessionHistory     历史消息
     * @param onStep             每个执行步骤的回调
     * @param onThinkingToken    T10：THINKING 阶段实时 token 回调（用于 UI 思考气泡流式显示）
     * @param onToken            最终回复流式 token 回调
     * @param onDone             完成回调
     * @param onError            错误回调
     */
    fun runAgent(
        project: Project,
        userMessage: String,
        sessionHistory: List<Map<String, Any>>,
        onStep: (AgentStep) -> Unit,
        onThinkingToken: (String) -> Unit = {},  // T10：思考阶段实时 token
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit,
        onCheckpointCreated: (List<Map<String, Any>>) -> Unit = {},  // P2-9：Checkpoint 列表推送回调
        onClarifyNeeded: (List<String>) -> Unit = {}  // B3：需求澄清回调（推送澄清卡片）
    ) {
        val llmService = LlmService.getInstance()

        cancelFlag.set(false)  // 重置取消标志

        Thread {
            // ── 多文件队列：为本次 Agent 任务创建独立 session ──────────────
            val taskId = MultiFileDiffManager.newTaskId()
            MultiFileDiffManager.beginSession(taskId)
            AgentToolExecutor.currentTaskId = taskId
            log.info("AgentService: 开始 Agent 任务 [$taskId]")

            // ── P2-9：任务开始前创建 Checkpoint ────────────────────────────
            val checkpointId = CheckpointManager.createCheckpoint(project, userMessage)
            AgentToolExecutor.currentCheckpointId = checkpointId
            // 推送最新 Checkpoint 列表到 UI（底部时间线）
            onCheckpointCreated(CheckpointManager.getSummaryList())

            try {
                // 自动获取项目结构快照（根目录文件树）
                val projectStructure = AgentToolExecutor.getProjectStructure(project)

                // 采集当前编辑器上下文（当前文件、光标位置、PSI 类/方法名、选中代码）
                val editorCtx = com.codeforge.plugin.idea.service.EditorContextProvider
                    .getFormattedContext(project)

                // P1-5：加载项目规则文件（.codeforge.md 或 ~/.codeforge/global.md）
                val projectRules = com.codeforge.plugin.idea.util.ProjectRulesLoader.load(project)

                // 构建带工具定义的 system 消息
                val systemMsg = mapOf<String, Any>(
                    "role" to "system",
                    "content" to buildString {
                        // P1-5：优先注入项目规则（放在最前面，确保 LLM 优先遵从）
                        if (projectRules.isNotBlank()) {
                            append("== 项目自定义规则（请严格遵守以下规则，优先级最高）==\n")
                            append(projectRules)
                            append("\n\n")
                        }
                        append("你是 CodeForge AI 助手，运行在 IntelliJ IDEA 中。\n")
                        append("你正在协助用户处理当前打开的代码仓库，项目名称：${project.name}\n\n")
                        append("== 项目文件结构 ==\n")
                        append(projectStructure)
                        if (editorCtx.isNotBlank()) {
                            append("\n\n== 用户当前编辑器状态 ==\n")
                            append(editorCtx)
                        }
                        append("\n\n== 可用工具 ==\n")
                        append(AgentToolExecutor.TOOL_DEFINITIONS)
                        // B2：自动注入记忆
                        val memoryPrompt = com.codeforge.plugin.idea.agent.MemoryManager.formatAsPrompt(project.basePath ?: "")
                        if (memoryPrompt.isNotBlank()) {
                            append("\n\n")
                            append(memoryPrompt)
                        }
                        append("""

重要规则：
- 用户正在查看的文件已在"当前编辑器状态"中提供，回答时优先参考该上下文
- 回答任何关于项目的问题时，必须先用 read_file 或 list_files 工具读取实际文件内容，不要凭空猜测
- 使用工具时严格按照格式，每次只调用一个工具，等待结果后再继续
- 当任务完成时，直接给出最终回复，不要再调用工具
- 不要重复调用同一个工具和路径，避免无限循环
- 如果用户的需求描述不清晰或缺少关键信息，在回复前先输出 `<clarify>问题1|问题2|问题3</clarify>` 标签，列出需要澄清的问题，等待用户回答后再继续""")
                    })

                // 当前对话消息列表
                val messages = mutableListOf<Map<String, Any>>()
                messages.add(systemMsg)
                messages.addAll(sessionHistory.filter { it["role"] != "system" })
                messages.add(mapOf("role" to "user", "content" to userMessage))

                // 收集所有工具调用，最后汇总显示
                val allToolCalls = mutableListOf<Pair<String, String>>() // tool → result summary
                var continueLoop = true

                while (continueLoop && !cancelFlag.get()) {
                    val responseBuffer = StringBuilder()
                    val done = AtomicBoolean(false)
                    var error: Exception? = null

                    // ── T10：通知 UI 进入 THINKING 阶段（显示思考气泡）──────
                    onStep(AgentStep(StepType.THINKING, ""))

                    // ── T10：流式收集 LLM 响应，同时通过 onThinkingToken 实时推送到思考气泡 ──
                    llmService.chatStream(
                        messages = messages,
                        onToken = { token ->
                            responseBuffer.append(token)
                            // 实时推送思考 token 到 UI（window.appendThinkingToken）
                            onThinkingToken(token)
                        },
                        onDone = { done.set(true) },
                        onError = { ex -> error = ex; done.set(true) }
                    )

                    // 等待 LLM 完成（最多 120 秒）
                    var waited = 0
                    while (!done.get() && waited < 120_000) {
                        Thread.sleep(100)
                        waited += 100
                    }

                    if (error != null) { onError(error!!); return@Thread }

                    val response = responseBuffer.toString()
                    if (response.isBlank()) {
                        onError(Exception("模型返回空响应，请检查 API Key 或切换模型重试"))
                        return@Thread
                    }
                    messages.add(mapOf("role" to "assistant", "content" to response))

                    // B3：检查是否有 <clarify> 标签
                    if (AgentToolExecutor.hasClarify(response)) {
                        val questions = AgentToolExecutor.parseClarify(response)
                        // 推送澄清卡片到 UI 并等待答案
                        onStep(AgentStep(StepType.THINKING, ""))
                        onClarifyNeeded(questions)
                        // 等待 JS Bridge 设置答案（轮询，最多等 5 分钟）
                        var waited = 0
                        while (pendingClarifyAnswer == null && waited < 300_000 && !cancelFlag.get()) {
                            Thread.sleep(200)
                            waited += 200
                        }
                        val answer = pendingClarifyAnswer ?: "（用户未回答）"
                        pendingClarifyAnswer = null
                        messages.add(mapOf(
                            "role" to "user",
                            "content" to "用户回答：$answer\n\n请根据用户回答继续执行任务。"
                        ))
                        onStep(AgentStep(StepType.THINKING, ""))
                        continue  // 继续下一轮循环
                    }

                    // 检查是否有工具调用
                    if (AgentToolExecutor.hasToolCall(response)) {
                        val toolResults = AgentToolExecutor.parseAndExecute(project, response)

                        toolResults.forEach { result ->
                            val fullResult = if (result.success) result.output
                                            else "❌ 工具执行失败: ${result.error}"

                            // ── T10：推送工具调用卡片数据（window.addToolCallCard）──
                            // 从响应中提取工具参数摘要
                            val toolInputSummary = extractToolInputSummary(response, result.tool)
                            onStep(AgentStep(
                                StepType.TOOL_CALL,
                                if (result.success) "success" else "error",
                                toolName = result.tool,
                                toolResult = result.output,
                                toolInput = toolInputSummary  // T10：工具参数摘要
                            ))

                            messages.add(mapOf(
                                "role" to "user",
                                "content" to "工具执行结果：\n$fullResult\n\n请继续。"
                            ))
                        }

                        // ── A8：错误自动修复循环 ─────────────────────────────────
                        val settings = com.codeforge.plugin.idea.settings.CodeForgeSettings.getInstance()
                        if (settings.autoFixEnabled) {
                            val failedTerminal = toolResults.find {
                                it.tool == "run_terminal" && !it.success
                            }
                            if (failedTerminal != null) {
                                // 提取错误输出
                                val errorOutput = failedTerminal.output.ifBlank { failedTerminal.error ?: "" }
                                var retryCount = 0
                                val maxRetries = settings.autoFixMaxRetries
                                var autoFixed = false

                                while (retryCount < maxRetries && !autoFixed && !cancelFlag.get()) {
                                    retryCount++
                                    onStep(AgentStep(
                                        StepType.THINKING,
                                        "🔄 第 $retryCount 次自动修复尝试（最多 $maxRetries 次）..."
                                    ))

                                    // 将错误信息注入对话历史，请求 LLM 修复
                                    val fixMessages = messages.toMutableList()
                                    fixMessages.add(mapOf(
                                        "role" to "user",
                                        "content" to "上次命令执行失败，错误输出如下：\n$errorOutput\n\n请分析错误原因并生成修复后的命令。输出格式：\n<tool_call>\n{\"tool\": \"run_terminal\", \"command\": \"修复后的命令\"}\n</tool_call>"
                                    ))

                                    val fixBuffer = StringBuilder()
                                    val fixDone = AtomicBoolean(false)
                                    llmService.chatStream(
                                        messages = fixMessages,
                                        onToken = { token -> fixBuffer.append(token) },
                                        onDone = { fixDone.set(true) },
                                        onError = { /* 忽略修复请求的错误 */ }
                                    )

                                    var waited = 0
                                    while (!fixDone.get() && waited < 60_000) {
                                        Thread.sleep(100)
                                        waited += 100
                                    }

                                    val fixResponse = fixBuffer.toString()
                                    if (AgentToolExecutor.hasToolCall(fixResponse)) {
                                        val fixResults = AgentToolExecutor.parseAndExecute(project, fixResponse)
                                        val terminalResult = fixResults.find { it.tool == "run_terminal" }
                                        if (terminalResult != null && terminalResult.success) {
                                            // 修复成功，继续正常流程
                                            val successMsg = "工具执行结果：\n${terminalResult.output}\n\n请继续。"
                                            messages.add(mapOf("role" to "user", "content" to successMsg))
                                            onStep(AgentStep(
                                                StepType.TOOL_CALL, "success",
                                                toolName = "run_terminal",
                                                toolResult = terminalResult.output,
                                                toolInput = "auto-fix attempt $retryCount"
                                            ))
                                            autoFixed = true
                                        } else {
                                            val failMsg = "工具执行结果：\n${terminalResult?.error ?: "修复失败"}\n\n请继续。"
                                            messages.add(mapOf("role" to "user", "content" to failMsg))
                                        }
                                    } else {
                                        // LLM 没有输出工具调用，修复结束
                                        messages.add(mapOf("role" to "user", "content" to "修复尝试完成，请继续或结束任务。"))
                                        autoFixed = true
                                    }
                                }
                            }
                        }

                        // 工具全部执行完后，再发 THINKING 通知 UI 进入下一轮等待
                        onStep(AgentStep(StepType.THINKING, ""))
                    } else {
                        // 没有工具调用 = 任务完成，模拟流式打字输出
                        continueLoop = false
                        onStep(AgentStep(StepType.RESPONSE, response))
                        // 流式打字效果：根据内容长度动态调整 chunk 大小和延迟
                        // 目标：整体输出时间控制在 3~6 秒内，短内容慢一点，长内容快一点
                        val totalLen = response.length
                        val targetMs = when {
                            totalLen < 200  -> 3000L   // 短内容：3秒打完
                            totalLen < 800  -> 4000L   // 中等：4秒
                            totalLen < 2000 -> 5000L   // 长：5秒
                            else            -> 6000L   // 超长：6秒
                        }
                        // 每次推送的字符数（动态）
                        val chunkSize = maxOf(1, totalLen / (targetMs / 20).toInt())
                        val delayMs = 20L  // 固定20ms间隔，chunkSize 动态控制速度

                        var offset = 0
                        while (offset < response.length) {
                            val end = minOf(offset + chunkSize, response.length)
                            onToken(response.substring(offset, end))
                            offset = end
                            Thread.sleep(delayMs)
                        }
                    }
                }

                // ── 任务完成：展示多文件变更确认面板 ──────────────────────────
                AgentToolExecutor.currentTaskId = null
                AgentToolExecutor.currentCheckpointId = null  // P2-9：清理 Checkpoint ID
                MultiFileDiffManager.showReviewPanel(project, taskId)

                onDone()

            } catch (e: Exception) {
                log.error("Agent 执行异常", e)
                // 异常时也要清理 taskId 和 checkpointId，避免泄露
                AgentToolExecutor.currentTaskId = null
                AgentToolExecutor.currentCheckpointId = null  // P2-9
                onError(e)
            }
        }.start()
    }

    // ==================== Spec 模式 ====================

    /**
     * Spec 模式第一步：生成执行计划
     *
     * @param userRequirement 用户需求描述
     * @param onPlanToken     计划内容流式回调
     * @param onPlanReady     计划生成完成，返回解析出的步骤列表
     * @param onError         错误回调
     */
    fun generatePlan(
        userRequirement: String,
        sessionHistory: List<Map<String, Any>>,
        onPlanToken: (String) -> Unit,
        onPlanReady: (List<PlanStep>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val llmService = LlmService.getInstance()

        Thread {
            val planBuffer = StringBuilder()
            val done = AtomicBoolean(false)

            val messages = mutableListOf<Map<String, Any>>()
            messages.add(mapOf(
                "role" to "system",
                "content" to """你是一个专业的软件工程 AI 助手。
用户会描述一个编程任务，你需要生成一个清晰的执行计划。

计划格式（严格按照此格式输出）：
## 执行计划

### 步骤 1：[步骤标题]
[步骤详细描述]

### 步骤 2：[步骤标题]
[步骤详细描述]

...

生成 3-7 个步骤，每步骤清晰具体，可操作。"""
            ))
            messages.addAll(sessionHistory.filter { it["role"] != "system" })
            messages.add(mapOf("role" to "user", "content" to "请为以下任务生成执行计划：\n\n$userRequirement"))

            llmService.chatStream(
                messages = messages,
                onToken = { token -> planBuffer.append(token); onPlanToken(token) },
                onDone = {
                    done.set(true)
                    val steps = parsePlanSteps(planBuffer.toString())
                    onPlanReady(steps)
                },
                onError = { ex -> done.set(true); onError(ex) }
            )
        }.start()
    }

    /**
     * Spec 模式第二步：执行计划（用户确认后调用）
     *
     * @param plan            确认的计划步骤
     * @param userRequirement 原始需求
     * @param project         当前项目
     * @param onStepStart     某步骤开始执行
     * @param onStepToken     步骤执行流式 token
     * @param onStepDone      某步骤完成
     * @param onAllDone       全部完成
     * @param onError         错误回调
     */
    fun executePlan(
        plan: List<PlanStep>,
        userRequirement: String,
        project: Project,
        onStepStart: (PlanStep) -> Unit,
        onStepToken: (String) -> Unit,
        onStepDone: (PlanStep) -> Unit,
        onAllDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val llmService = LlmService.getInstance()

        Thread {
            try {
                val planText = plan.joinToString("\n") { "步骤 ${it.index}：${it.title}\n${it.description}" }

                plan.forEach { step ->
                    onStepStart(step)
                    step.status = StepStatus.RUNNING

                    val stepBuffer = StringBuilder()
                    val done = AtomicBoolean(false)

                    val messages = listOf(
                        mapOf<String, Any>(
                            "role" to "system",
                            "content" to """你是 CodeForge AI 助手，正在按计划执行一个编程任务。
${AgentToolExecutor.TOOL_DEFINITIONS}"""
                        ),
                        mapOf<String, Any>(
                            "role" to "user",
                            "content" to """用户的原始需求：
$userRequirement

完整执行计划：
$planText

请执行**步骤 ${step.index}：${step.title}**。
${step.description}

执行时如需读取或修改文件，请使用工具调用。"""
                        )
                    )

                    llmService.chatStream(
                        messages = messages,
                        onToken = { token -> stepBuffer.append(token); onStepToken(token) },
                        onDone = {
                            // 处理工具调用
                            if (AgentToolExecutor.hasToolCall(stepBuffer.toString())) {
                                AgentToolExecutor.parseAndExecute(project, stepBuffer.toString())
                            }
                            step.status = StepStatus.DONE
                            done.set(true)
                            onStepDone(step)
                        },
                        onError = { ex ->
                            step.status = StepStatus.ERROR
                            done.set(true)
                            onError(ex)
                        }
                    )

                    // 等待当前步骤完成
                    var waited = 0
                    while (!done.get() && waited < 60_000) {
                        Thread.sleep(100)
                        waited += 100
                    }
                }

                onAllDone()
            } catch (e: Exception) {
                log.error("执行计划异常", e)
                onError(e)
            }
        }.start()
    }

    // ==================== 私有辅助 ====================

    /**
     * T10：从 LLM 响应中提取指定工具调用的参数摘要，用于工具卡片展示。
     *
     * 例如 read_file → "src/main/Foo.kt"
     *      write_file → "src/main/Bar.kt"
     *      search_code → "keyword: xxx"
     *      run_terminal → "git status"
     */
    private fun extractToolInputSummary(llmResponse: String, toolName: String): String {
        return try {
            val tagRegex = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
            val matchJson = tagRegex.find(llmResponse)?.groupValues?.get(1) ?: return ""
            val json = com.google.gson.Gson().fromJson(matchJson, Map::class.java)
            when (toolName) {
                "read_file"    -> json["path"] as? String ?: ""
                "write_file"   -> json["path"] as? String ?: ""
                "list_files"   -> json["path"] as? String ?: "."
                "search_code"  -> {
                    val kw = json["keyword"] as? String ?: ""
                    val pattern = json["filePattern"] as? String
                    if (pattern != null) "$kw ($pattern)" else kw
                }
                "run_terminal" -> {
                    val cmd = json["command"] as? String ?: ""
                    if (cmd.length > 80) cmd.take(80) + "…" else cmd
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 解析 LLM 生成的计划文本为步骤列表
     */
    fun parsePlanSteps(planText: String): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        val stepRegex = Regex("###\\s*步骤\\s*(\\d+)[：:]\\s*(.+?)\\n([\\s\\S]*?)(?=###|$)")

        stepRegex.findAll(planText).forEach { match ->
            val index = match.groupValues[1].toIntOrNull() ?: (steps.size + 1)
            val title = match.groupValues[2].trim()
            val description = match.groupValues[3].trim()
            if (title.isNotBlank()) {
                steps.add(PlanStep(index, title, description))
            }
        }

        // 如果正则解析失败，做简单的行解析
        if (steps.isEmpty()) {
            planText.lines().forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.matches(Regex("\\d+[.。、]\\s*.+"))) {
                    val title = trimmed.replace(Regex("^\\d+[.。、]\\s*"), "")
                    steps.add(PlanStep(i + 1, title, ""))
                }
            }
        }

        return steps
    }
}

