package com.codeforge.plugin.idea.agent

import com.codeforge.plugin.idea.diff.MultiFileDiffManager
import com.codeforge.plugin.idea.service.LlmService
import com.codeforge.plugin.idea.settings.CodeForgeSettings
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

    // ==================== A6：Plan Mode ====================

    /** Plan Mode 用户决策 */
    sealed class PlanDecision {
        object Confirm : PlanDecision()
        object Cancel : PlanDecision()
        data class Edit(val modifiedPlan: String) : PlanDecision()
    }

    /** Plan Mode 等待队列（runAgent 阻塞等待用户决策） */
    @Volatile
    private var planDecisionQueue: java.util.concurrent.LinkedBlockingQueue<PlanDecision>? = null

    /**
     * ForgeChatPanel 收到 JS confirmPlan / cancelPlan / editPlan 消息后调用此方法，
     * 唤醒 runAgent 中阻塞等待的线程。
     */
    fun signalPlanDecision(decision: PlanDecision) {
        planDecisionQueue?.offer(decision)
            ?: log.warn("AgentService: signalPlanDecision 调用时没有等待中的 Plan")
    }

    /** 从 LLM 响应中提取 <plan>...</plan> 内容，无则返回 null */
    private fun extractPlan(response: String): String? {
        val regex = Regex("<plan>([\\s\\S]*?)</plan>", RegexOption.IGNORE_CASE)
        return regex.find(response)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

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
        isPlanMode: Boolean = false,             // A6：Plan Mode — 先出计划再执行
        onPlanReady: (String) -> Unit = {}       // A6：Plan Mode — 计划就绪，等待用户确认
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
                        val maxAutoFix = CodeForgeSettings.getInstance().autoFixMaxRetries
                        // A6：Plan Mode 指令（放在工具定义之后，优先级高）
                        if (isPlanMode) {
                            append("""

== Plan Mode（规划模式）==
在调用任何工具之前，必须先以如下格式输出完整执行计划，等待用户确认后再开始执行：

<plan>
步骤 1：[步骤标题]
[详细描述]

步骤 2：[步骤标题]
[详细描述]
...
</plan>

用户确认后，按计划逐步调用工具执行。若用户修改了计划，按修改后的版本执行。""")
                        }
                        append("""

重要规则：
- 用户正在查看的文件已在"当前编辑器状态"中提供，回答时优先参考该上下文
- 回答任何关于项目的问题时，必须先用 read_file 或 list_files 工具读取实际文件内容，不要凭空猜测
- 使用工具时严格按照格式，每次只调用一个工具，等待结果后再继续
- 当任务完成时，直接给出最终回复，不要再调用工具
- 不要重复调用同一个工具和路径，避免无限循环
- 当 run_terminal 执行失败时（exit code ≠ 0），分析错误输出，定位并修复问题，然后重新执行命令；最多尝试 $maxAutoFix 次，超过后直接告知用户失败原因""")
                    })

                // 当前对话消息列表
                val messages = mutableListOf<Map<String, Any>>()
                messages.add(systemMsg)
                messages.addAll(sessionHistory.filter { it["role"] != "system" })
                messages.add(mapOf("role" to "user", "content" to userMessage))

                // 收集所有工具调用，最后汇总显示
                val allToolCalls = mutableListOf<Pair<String, String>>() // tool → result summary
                var continueLoop = true

                // A8：错误自动修复循环 — 追踪 run_terminal 连续失败次数
                var terminalFailCount = 0
                val maxAutoFix = CodeForgeSettings.getInstance().autoFixMaxRetries

                // A6：Plan Mode — 计划只检测一次，避免重复弹出
                var planShown = false

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

                    // ── A6：Plan Mode — 检测 <plan> 标签，暂停等待用户确认 ───────
                    if (isPlanMode && !planShown) {
                        val planContent = extractPlan(response)
                        if (planContent != null) {
                            planShown = true
                            log.info("AgentService: Plan Mode — 检测到计划，等待用户确认（最多10分钟）")

                            val queue = java.util.concurrent.LinkedBlockingQueue<PlanDecision>(1)
                            planDecisionQueue = queue
                            onPlanReady(planContent)  // 通知 UI 展示计划确认卡片

                            val decision = queue.poll(10, java.util.concurrent.TimeUnit.MINUTES)
                                ?: PlanDecision.Cancel
                            planDecisionQueue = null

                            when (decision) {
                                PlanDecision.Cancel -> {
                                    log.info("AgentService: Plan Mode — 用户取消")
                                    continueLoop = false
                                }
                                PlanDecision.Confirm -> {
                                    log.info("AgentService: Plan Mode — 用户确认，注入执行指令")
                                    messages.add(mapOf(
                                        "role" to "user",
                                        "content" to "计划已确认，请按计划逐步调用工具执行各步骤。"
                                    ))
                                }
                                is PlanDecision.Edit -> {
                                    log.info("AgentService: Plan Mode — 用户修改计划后确认")
                                    messages.add(mapOf(
                                        "role" to "user",
                                        "content" to "已修改计划如下，请按修改后的计划执行：\n\n${decision.modifiedPlan}"
                                    ))
                                }
                            }
                            continue  // 无论何种决策，均重新进入循环顶部（Cancel 时 continueLoop=false 自然退出）
                        }
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

                            // A8：错误自动修复循环 — 追踪 run_terminal 连续失败次数
                            if (result.tool == "run_terminal") {
                                if (!result.success) {
                                    terminalFailCount++
                                    log.info("AgentService: run_terminal 失败 ($terminalFailCount/$maxAutoFix)")
                                    if (maxAutoFix > 0 && terminalFailCount >= maxAutoFix) {
                                        log.warn("AgentService: 达到自动修复上限 ($maxAutoFix 次)，注入停止指令")
                                        messages.add(mapOf(
                                            "role" to "user",
                                            "content" to "工具执行结果：\n$fullResult\n\n" +
                                                "⚠️ 已连续 $maxAutoFix 次尝试修复失败，请停止继续尝试。" +
                                                "直接向用户说明：失败的根本原因是什么，以及需要用户手动处理的步骤。"
                                        ))
                                        return@forEach  // 跳过后面的普通 message.add
                                    }
                                } else {
                                    terminalFailCount = 0  // 成功则重置计数器
                                }
                            }

                            messages.add(mapOf(
                                "role" to "user",
                                "content" to "工具执行结果：\n$fullResult\n\n请继续。"
                            ))
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

    // ==================== A7：行内编辑 ====================

    /**
     * 行内编辑 — 对选中代码执行指令级修改
     *
     * @param project      当前项目
     * @param selectedCode 选中的原始代码
     * @param instruction  用户的修改指令（如"优化性能"、"添加注释"）
     * @param onResult     修改完成，返回新代码（纯文本，无 markdown 包装）
     * @param onError      错误回调
     */
    fun runInlineEdit(
        project: Project,
        selectedCode: String,
        instruction: String,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val llmService = LlmService.getInstance()

        Thread {
            try {
                val systemMsg = mapOf<String, Any>(
                    "role" to "system",
                    "content" to "你是专业的代码编辑助手，运行在 IntelliJ IDEA 中。\n" +
                        "规则：\n" +
                        "1. 只返回修改后的代码本身，不要任何解释\n" +
                        "2. 不要用 markdown 代码块（```）包裹\n" +
                        "3. 严格保持原有的缩进风格和代码结构\n" +
                        "4. 只做用户指令要求的最小改动"
                )
                val userMsg = mapOf<String, Any>(
                    "role" to "user",
                    "content" to "修改指令：$instruction\n\n待修改代码：\n$selectedCode"
                )

                val resultBuilder = StringBuilder()
                val latch = java.util.concurrent.CountDownLatch(1)
                var streamError: Exception? = null

                llmService.chatStream(
                    messages = listOf(systemMsg, userMsg),
                    onToken = { token -> resultBuilder.append(token) },
                    onDone = { latch.countDown() },
                    onError = { ex -> streamError = ex; latch.countDown() }
                )

                latch.await(60, java.util.concurrent.TimeUnit.SECONDS)

                if (streamError != null) {
                    onError(streamError!!)
                } else {
                    onResult(stripCodeFences(resultBuilder.toString().trim()))
                }
            } catch (ex: Exception) {
                log.error("runInlineEdit 失败", ex)
                onError(ex)
            }
        }.also { it.isDaemon = true }.start()
    }

    /** 剥除 LLM 可能多返回的 markdown 代码块标记 */
    private fun stripCodeFences(text: String): String {
        val lines = text.lines()
        if (lines.isEmpty()) return text
        var start = 0
        var end = lines.size - 1
        if (lines.getOrNull(start)?.trimStart()?.startsWith("```") == true) start++
        if (lines.getOrNull(end)?.trim() == "```") end--
        return if (start > end) text else lines.subList(start, end + 1).joinToString("\n")
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

