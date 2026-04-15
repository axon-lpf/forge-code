package com.codeforge.plugin.idea.agent

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

    fun cancel() {
        cancelFlag.set(true)
        log.info("AgentService: 收到取消指令")
    }

    /** Agent 执行状态 */
    data class AgentStep(
        val type: StepType,
        val content: String,
        val toolName: String? = null,
        val toolResult: String? = null
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
     * @param project       当前项目
     * @param userMessage   用户输入
     * @param sessionHistory 历史消息
     * @param onStep        每个执行步骤的回调
     * @param onToken       流式 token 回调
     * @param onDone        完成回调
     * @param onError       错误回调
     */
    fun runAgent(
        project: Project,
        userMessage: String,
        sessionHistory: List<Map<String, Any>>,
        onStep: (AgentStep) -> Unit,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        val llmService = LlmService.getInstance()

        cancelFlag.set(false)  // 重置取消标志

        Thread {
            try {
                // 自动获取项目结构快照（根目录文件树）
                val projectStructure = AgentToolExecutor.getProjectStructure(project)

                // 构建带工具定义的 system 消息
                val systemMsg = mapOf<String, Any>(
                    "role" to "system",
                    "content" to """你是 CodeForge AI 助手，运行在 IntelliJ IDEA 中。
你正在协助用户处理当前打开的代码仓库，项目名称：${project.name}

== 项目文件结构 ==
$projectStructure

== 可用工具 ==
${AgentToolExecutor.TOOL_DEFINITIONS}

重要规则：
- 回答任何关于项目的问题时，必须先用 read_file 或 list_files 工具读取实际文件内容，不要凭空猜测
- 使用工具时严格按照格式，每次只调用一个工具，等待结果后再继续
- 当任务完成时，直接给出最终回复，不要再调用工具
- 不要重复调用同一个工具和路径，避免无限循环""")

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

                    // 通知 UI：AI 正在思考
                    onStep(AgentStep(StepType.THINKING, ""))

                    // 静默收集完整响应，判断有无工具调用
                    llmService.chatStream(
                        messages = messages,
                        onToken = { token -> responseBuffer.append(token) },
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

                    // 检查是否有工具调用
                    if (AgentToolExecutor.hasToolCall(response)) {
                        val toolResults = AgentToolExecutor.parseAndExecute(project, response)

                        toolResults.forEach { result ->
                            val fullResult = if (result.success) result.output
                                            else "❌ 工具执行失败: ${result.error}"

                            // 通知 UI：工具调用结果（在当前 thinking 消除后追加卡片）
                            onStep(AgentStep(
                                StepType.TOOL_CALL,
                                if (result.success) "success" else "error",
                                toolName = result.tool,
                                toolResult = result.output
                            ))

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

                onDone()

            } catch (e: Exception) {
                log.error("Agent 执行异常", e)
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

