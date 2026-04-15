package com.codeforge.plugin.idea.toolwindow

import com.codeforge.plugin.idea.service.LlmService
import com.codeforge.plugin.idea.service.SessionManager
import com.codeforge.plugin.idea.settings.CodeForgeSettings
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * CodeForge 聊天主面板
 *
 * 使用 JCEF（内嵌 Chromium）加载 webui/index.html，
 * 通过 JBCefJSQuery 实现 JS ↔ Kotlin 双向通信。
 */
class CodeForgeChatPanel(private val project: Project) {

    private val log = logger<CodeForgeChatPanel>()
    private val gson = Gson()
    private val llmService = LlmService.getInstance()

    /** 根容器 */
    private val rootPanel = JPanel(BorderLayout())

    /** JCEF 浏览器实例（懒加载） */
    private var browser: JBCefBrowser? = null

    /** JS → Kotlin 消息通道 */
    private var jsQuery: JBCefJSQuery? = null

    /** 当前正在进行的 SSE EventSource（用于取消） */
    private var currentEventSource: okhttp3.sse.EventSource? = null

    /** 当前会话 ID */
    private var currentSessionId: String? = null

    /** 当前 AI 回复的 buffer（流结束后保存到会话） */
    private val currentAiBuffer = StringBuilder()

    init {
        if (JBCefApp.isSupported()) {
            initJcef()
        } else {
            // JCEF 不可用（部分轻量 IDE），降级显示文字提示
            rootPanel.add(
                JLabel(
                    "<html><center>当前 IDE 不支持 JCEF<br>请升级到 IntelliJ IDEA 2023.1+</center></html>",
                    SwingConstants.CENTER
                )
            )
        }
    }

    fun getComponent(): JComponent = rootPanel

    // ==================== JCEF 初始化 ====================

    private fun initJcef() {
        val b = JBCefBrowser()
        this.browser = b

        // 注册 JS → Kotlin 消息通道
        val query = JBCefJSQuery.create(b as JBCefBrowserBase)
        this.jsQuery = query

        query.addHandler { request ->
            handleJsMessage(request)
            null  // 无需同步返回值，异步通过 executeJS 回调
        }

        // 页面加载完成后注入 JS 通道函数
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    injectBridge(b, query)
                    loadInitialData()
                }
            }
        }, b.cefBrowser)

        // 将 CSS/JS 内联到 HTML 后通过 loadHTML 加载
        // JCEF 无法加载 jar:// 协议，必须用 loadHTML 传入完整内容
        val html = buildInlinedHtml()
        b.loadHTML(html)

        rootPanel.add(b.component, BorderLayout.CENTER)
    }

    /**
     * 读取 webui 资源并将 CSS/JS 全部内联，生成可直接加载的完整 HTML 字符串
     */
    private fun buildInlinedHtml(): String {
        fun readResource(path: String): String =
            javaClass.getResourceAsStream(path)
                ?.bufferedReader(Charsets.UTF_8)
                ?.readText()
                ?: run {
                    log.warn("资源文件未找到: $path")
                    ""
                }

        val css    = readResource("/webui/css/style.css")
        val bridge = readResource("/webui/js/bridge.js")
        val marked = readResource("/webui/js/marked.min.js")
        val chat   = readResource("/webui/js/chat.js")

        // 读取原始 HTML，移除外部引用标签，改为内联
        var html = readResource("/webui/index.html")
        if (html.isEmpty()) return buildFallbackHtml()

        html = html
            .replace("""<link rel="stylesheet" href="css/style.css">""",
                     "<style>\n$css\n</style>")
            .replace("""<script src="js/bridge.js"></script>""",
                     "<script>\n$bridge\n</script>")
            .replace("""<script src="js/marked.min.js"></script>""",
                     if (marked.isNotEmpty()) "<script>\n$marked\n</script>" else "")
            .replace("""<script src="js/chat.js"></script>""",
                     "<script>\n$chat\n</script>")

        return html
    }

    /**
     * 向页面注入通信桥函数 window.ideaBridge(msg)
     * JS 端通过调用此函数向 Kotlin 发消息
     */
    private fun injectBridge(b: JBCefBrowser, query: JBCefJSQuery) {
        val js = """
            window.ideaBridge = function(message) {
                ${query.inject("JSON.stringify(message)")}
            };
            console.log('[CodeForge] JS Bridge 注入完成');
        """.trimIndent()
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
    }

    /**
     * 页面加载完成后，推送初始化数据（当前模型、设置等）
     */
    private fun loadInitialData() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val activeInfo = llmService.getActiveInfo()
            val providers = llmService.getProviderList()
            val settings = CodeForgeSettings.getInstance()

            // 获取系统用户名
            val sysUser = System.getProperty("user.name") ?: "You"
            val initData = mapOf(
                "backendOnline" to true,
                "activeProvider" to (activeInfo.provider ?: ""),
                "activeModel" to (activeInfo.model ?: ""),
                "models" to providers,
                "theme" to settings.theme,
                "language" to settings.language,
                "defaultMode" to settings.defaultMode,
                "projectName" to (project.name),
                "userName" to sysUser
            )
            executeJS("window.onInit && window.onInit(${gson.toJson(initData)})")
        }
    }

    // ==================== JS 消息处理 ====================

    /**
     * 处理来自 JS 的消息
     *
     * 消息格式: {"type": "xxx", ...}
     */
    private fun handleJsMessage(request: String) {
        try {
            val json = gson.fromJson(request, Map::class.java)
            val type = json["type"] as? String ?: return
            log.debug("收到 JS 消息: type=$type")

            when (type) {
                "sendMessage"   -> handleSendMessage(json)
                "cancelMessage" -> handleCancelMessage()
                "switchModel"   -> handleSwitchModel(json)
                "getModels"     -> handleGetModels()
                "newSession"    -> handleNewSession()
                "loadSession"   -> handleLoadSession(json)
                "deleteSession" -> handleDeleteSession(json)
                "renameSession" -> handleRenameSession(json)
                "getSessions"   -> handleGetSessions()
                "searchFiles"    -> handleSearchFiles(json)
                "readFileCtx"    -> handleReadFileCtx(json)
                "applyCode"      -> handleApplyCode(json)
                "openSettings"   -> handleOpenSettings()
                "runAgent"          -> handleRunAgent(json)
                "generatePlan"      -> handleGeneratePlan(json)
                "executePlan"       -> handleExecutePlan(json)
                "getChangedFiles"   -> handleGetChangedFiles()
                "reviewCode"        -> handleReviewCode(json)
                else -> log.warn("未知 JS 消息类型: $type")
            }
        } catch (e: Exception) {
            log.error("处理 JS 消息失败: $request", e)
        }
    }

    /** 处理发送消息（流式对话，带会话持久化） */
        private fun handleSendMessage(json: Map<*, *>) {
        val rawContent = json["content"] as? String ?: return
        val jsSessionId = json["sessionId"] as? String

        // 智能上下文注入：自动附加当前编辑器状态
        val editorContext = com.codeforge.plugin.idea.service.EditorContextProvider.getFormattedContext(project)
        val content = if (editorContext.isNotBlank()) {
            "$rawContent\n$editorContext"
        } else {
            rawContent
        }

        // 取消上一个未完成的流
        currentEventSource?.cancel()

        // 确定当前会话：JS 提供 sessionId 则用，否则创建/复用 Kotlin 端维护的会话
        val activeInfo = llmService.getActiveInfo()
        val sid = jsSessionId?.takeIf { it.isNotBlank() }
            ?: currentSessionId
            ?: run {
                val s = SessionManager.createSession(
                    provider = activeInfo.provider ?: "",
                    model = activeInfo.model ?: ""
                )
                currentSessionId = s.id
                s.id
            }
        currentSessionId = sid

        // 保存用户消息
        SessionManager.appendMessage(sid, "user", content)

        // 构建完整历史消息（含本次）
        val messages = SessionManager.getMessages(sid)

        // 重置 AI buffer
        currentAiBuffer.clear()

        llmService.chatStream(
            messages = messages,
            onToken = { token ->
                currentAiBuffer.append(token)
                val escaped = token
                    .replace("\\", "\\\\")
                    .replace("`", "\\`")
                    .replace("$", "\\$")
                executeJS("window.appendToken && window.appendToken(`$escaped`)")
            },
            onDone = {
                // 流结束：保存 AI 回复到会话
                val aiContent = currentAiBuffer.toString()
                if (aiContent.isNotBlank()) {
                    SessionManager.appendMessage(sid, "assistant", aiContent)
                }
                currentAiBuffer.clear()
                // 通知 JS 更新会话标题
                val summary = SessionManager.listSessions().find { it.id == sid }
                if (summary != null) {
                    val titleEscaped = summary.title.replace("\"", "\\\"")
                    executeJS("window.onSessionTitleUpdate && window.onSessionTitleUpdate(\"$sid\", \"$titleEscaped\")")
                }
                executeJS("window.onStreamDone && window.onStreamDone()")
            },
            onError = { ex ->
                currentAiBuffer.clear()
                val msg = (ex.message ?: "未知错误")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                executeJS("window.onError && window.onError(\"$msg\")")
            },
            onAutoRetry = { failedProvider, newProvider, newModel ->
                val failedDisplay = failedProvider.replace("\"", "\\\"")
                val newDisplay = newProvider.replace("\"", "\\\"")
                val modelDisplay = newModel.replace("\"", "\\\"")
                executeJS("window.onAutoRetry && window.onAutoRetry(\"$failedDisplay\", \"$newDisplay\", \"$modelDisplay\")")
            }
        )
    }

    /** 取消当前流式输出 */
    private fun handleCancelMessage() {
        // 取消普通流式
        currentEventSource?.cancel()
        currentEventSource = null
        // 取消 Agent 循环
        com.codeforge.plugin.idea.agent.AgentService.cancel()
        // 若有部分回复也保存
        val aiContent = currentAiBuffer.toString()
        val sid = currentSessionId
        if (sid != null && aiContent.isNotBlank()) {
            SessionManager.appendMessage(sid, "assistant", aiContent + " *(已取消)*")
        }
        currentAiBuffer.clear()
        executeJS("window.onStreamDone && window.onStreamDone()")
    }

    /** 切换模型 */
    private fun handleSwitchModel(json: Map<*, *>) {
        val provider = json["provider"] as? String ?: return
        val model = json["model"] as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val success = llmService.switchModel(provider, model)
            if (success) {
                val activeInfo = llmService.getActiveInfo()
                // 更新当前会话的模型记录
                currentSessionId?.let {
                    SessionManager.updateSessionModel(it, activeInfo.provider ?: "", activeInfo.model ?: "")
                }
                executeJS(
                    "window.onModelSwitched && window.onModelSwitched(" +
                            "\"${activeInfo.provider}\", \"${activeInfo.model}\")"
                )
                ApplicationManager.getApplication().invokeLater {
                    project.messageBus.syncPublisher(ModelChangedListener.TOPIC)
                        .onModelChanged(activeInfo.provider ?: "", activeInfo.model ?: "")
                }
            }
        }
    }

    /** 获取模型列表 */
    private fun handleGetModels() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val providers = llmService.getProviderList()
            val activeInfo = llmService.getActiveInfo()
            val response = mapOf(
                "activeProvider" to activeInfo.provider,
                "activeModel" to activeInfo.model,
                "providers" to providers
            )
            executeJS("window.updateModels && window.updateModels(${gson.toJson(response)})")
        }
    }

    /** 新建会话 */
    private fun handleNewSession() {
        val activeInfo = llmService.getActiveInfo()
        val session = SessionManager.createSession(
            provider = activeInfo.provider ?: "",
            model = activeInfo.model ?: ""
        )
        currentSessionId = session.id
        currentAiBuffer.clear()
        executeJS("window.onNewSession && window.onNewSession(${gson.toJson(mapOf("sessionId" to session.id))})")
    }

    /** 加载指定会话（切换会话） */
    private fun handleLoadSession(json: Map<*, *>) {
        val sessionId = json["sessionId"] as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val session = SessionManager.loadSession(sessionId) ?: return@executeOnPooledThread
            currentSessionId = sessionId
            currentAiBuffer.clear()
            // 构建消息列表供 JS 渲染
            val messagesJson = session.messages.map {
                mapOf("role" to it.role, "content" to it.content, "timestamp" to it.timestamp)
            }
            val response = mapOf(
                "sessionId" to session.id,
                "title" to session.title,
                "provider" to session.provider,
                "model" to session.model,
                "messages" to messagesJson
            )
            executeJS("window.onSessionLoaded && window.onSessionLoaded(${gson.toJson(response)})")
        }
    }

    /** 删除会话 */
    private fun handleDeleteSession(json: Map<*, *>) {
        val sessionId = json["sessionId"] as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            SessionManager.deleteSession(sessionId)
            if (currentSessionId == sessionId) {
                currentSessionId = null
            }
            // 重新推送会话列表
            pushSessionList()
        }
    }

    /** 重命名会话 */
    private fun handleRenameSession(json: Map<*, *>) {
        val sessionId = json["sessionId"] as? String ?: return
        val title = json["title"] as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            SessionManager.renameSession(sessionId, title)
            pushSessionList()
        }
    }

    /** 获取会话列表 */
    private fun handleGetSessions() {
        ApplicationManager.getApplication().executeOnPooledThread {
            pushSessionList()
        }
    }

    /** 推送会话列表到 JS */
    private fun pushSessionList() {
        val sessions = SessionManager.listSessions()
        val response = mapOf(
            "sessions" to sessions,
            "currentSessionId" to currentSessionId
        )
        executeJS("window.onSessionList && window.onSessionList(${gson.toJson(response)})")
    }

    /** 搜索项目文件（@引用触发） */
    private fun handleSearchFiles(json: Map<*, *>) {
        val keyword = json["keyword"] as? String ?: ""
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = com.codeforge.plugin.idea.service.FileContextProvider
                .searchFiles(project, keyword, limit = 20)
            executeJS("window.onFileSearchResult && window.onFileSearchResult(${gson.toJson(results)})")
        }
    }

    /** 读取文件内容（JS 确认选中文件后调用） */
    private fun handleReadFileCtx(json: Map<*, *>) {
        val path = json["path"] as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = com.codeforge.plugin.idea.service.FileContextProvider
                .readFile(project, path)
            if (result != null) {
                executeJS("window.onFileContent && window.onFileContent(${gson.toJson(result)})")
            }
        }
    }

    /** 将 AI 生成的代码应用到编辑器（Diff 视图） */
    private fun handleApplyCode(json: Map<*, *>) {
        val code = json["code"] as? String ?: return
        val language = json["language"] as? String ?: ""
        val fileName = json["fileName"] as? String
        com.codeforge.plugin.idea.util.CodeApplyManager.applyCode(project, code, language, fileName)
    }

    /** 打开插件设置页 */
    private fun handleOpenSettings() {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "CodeForge")
        }
    }

    /** Agent 模式执行 */
    private fun handleRunAgent(json: Map<*, *>) {
        val rawContent = json["content"] as? String ?: return

        // 智能上下文注入：自动附加当前编辑器状态
        val editorContext = com.codeforge.plugin.idea.service.EditorContextProvider.getFormattedContext(project)
        val content = if (editorContext.isNotBlank()) {
            "$rawContent\n$editorContext"
        } else {
            rawContent
        }

        val sid = currentSessionId ?: run {
            val activeInfo = llmService.getActiveInfo()
            val s = SessionManager.createSession(activeInfo.provider ?: "", activeInfo.model ?: "")
            currentSessionId = s.id; s.id
        }
        SessionManager.appendMessage(sid, "user", content)
        val history = SessionManager.getMessages(sid)
        currentAiBuffer.clear()

        com.codeforge.plugin.idea.agent.AgentService.runAgent(
            project = project,
            userMessage = content,
            sessionHistory = history,
            onStep = { step ->
                // toolResult 可能含有文件内容（反引号、$、\），需要 Base64 编码避免 JS 注入问题
                val safeResult = java.util.Base64.getEncoder()
                    .encodeToString((step.toolResult ?: "").toByteArray(Charsets.UTF_8))
                val stepJson = gson.toJson(mapOf(
                    "type" to step.type.name,
                    "content" to step.content,
                    "toolName" to (step.toolName ?: ""),
                    "toolResultB64" to safeResult   // Base64 编码的工具结果
                ))
                executeJS("window.onAgentStep && window.onAgentStep($stepJson)")
            },
            onToken = { token ->
                currentAiBuffer.append(token)
                // 分块推送 token，每块约 8 字符，产生打字效果
                token.chunked(8).forEach { chunk ->
                    val escaped = chunk.replace("\\","\\\\").replace("`","\\`").replace("$","\\$")
                    executeJS("window.appendToken && window.appendToken(`$escaped`)")
                }
            },
            onDone = {
                val aiContent = currentAiBuffer.toString()
                if (aiContent.isNotBlank()) SessionManager.appendMessage(sid, "assistant", aiContent)
                currentAiBuffer.clear()
                executeJS("window.onStreamDone && window.onStreamDone()")
            },
            onError = { ex ->
                currentAiBuffer.clear()
                val msg = (ex.message ?: "未知错误").replace("\"","\\\"").replace("\n","\\n")
                executeJS("window.onError && window.onError(\"$msg\")")
            }
        )
    }

    /** Spec 模式：生成执行计划 */
    private fun handleGeneratePlan(json: Map<*, *>) {
        val content = json["content"] as? String ?: return
        val sid = currentSessionId ?: run {
            val activeInfo = llmService.getActiveInfo()
            val s = SessionManager.createSession(activeInfo.provider ?: "", activeInfo.model ?: "")
            currentSessionId = s.id; s.id
        }
        val history = SessionManager.getMessages(sid)
        currentAiBuffer.clear()

        com.codeforge.plugin.idea.agent.AgentService.generatePlan(
            userRequirement = content,
            sessionHistory = history,
            onPlanToken = { token ->
                currentAiBuffer.append(token)
                val escaped = token.replace("\\","\\\\").replace("`","\\`").replace("$","\\$")
                executeJS("window.appendToken && window.appendToken(`$escaped`)")
            },
            onPlanReady = { steps ->
                val stepsJson = gson.toJson(steps)
                executeJS("window.onPlanReady && window.onPlanReady($stepsJson)")
                executeJS("window.onStreamDone && window.onStreamDone()")
            },
            onError = { ex ->
                val msg = (ex.message ?: "未知错误").replace("\"","\\\"").replace("\n","\\n")
                executeJS("window.onError && window.onError(\"$msg\")")
            }
        )
    }

    /** Spec 模式：执行已确认的计划 */
    @Suppress("UNCHECKED_CAST")
    private fun handleExecutePlan(json: Map<*, *>) {
        val requirement = json["requirement"] as? String ?: return
        val planRaw = json["plan"] as? List<*> ?: return
        val steps = planRaw.mapIndexedNotNull { i, item ->
            if (item is Map<*, *>) {
                com.codeforge.plugin.idea.agent.AgentService.PlanStep(
                    index = (item["index"] as? Double)?.toInt() ?: (i + 1),
                    title = item["title"] as? String ?: "",
                    description = item["description"] as? String ?: ""
                )
            } else null
        }

        com.codeforge.plugin.idea.agent.AgentService.executePlan(
            plan = steps,
            userRequirement = requirement,
            project = project,
            onStepStart = { step ->
                val stepJson = gson.toJson(mapOf("index" to step.index, "title" to step.title, "status" to "RUNNING"))
                executeJS("window.onPlanStepUpdate && window.onPlanStepUpdate($stepJson)")
            },
            onStepToken = { token ->
                val escaped = token.replace("\\","\\\\").replace("`","\\`").replace("$","\\$")
                executeJS("window.appendToken && window.appendToken(`$escaped`)")
            },
            onStepDone = { step ->
                val stepJson = gson.toJson(mapOf("index" to step.index, "title" to step.title, "status" to "DONE"))
                executeJS("window.onPlanStepUpdate && window.onPlanStepUpdate($stepJson)")
            },
            onAllDone = {
                executeJS("window.onPlanAllDone && window.onPlanAllDone()")
                executeJS("window.onStreamDone && window.onStreamDone()")
            },
            onError = { ex ->
                val msg = (ex.message ?: "未知错误").replace("\"","\\\"").replace("\n","\\n")
                executeJS("window.onError && window.onError(\"$msg\")")
            }
        )
    }

    /** 获取 Git 变更文件列表，返回给前端 */
    private fun handleGetChangedFiles() {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val files = com.codeforge.plugin.idea.review.ReviewService.getChangedFiles(project)
            val jsonList = gson.toJson(files)
            executeJS("window.onChangedFiles && window.onChangedFiles($jsonList)")
        }
    }

    /** 触发 AI 代码审查 */
    private fun handleReviewCode(json: Map<*, *>) {
        @Suppress("UNCHECKED_CAST")
        val filePaths = (json["files"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val stagedRaw = json["staged"] as? Boolean ?: false

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            // 1. 获取 diff 内容
            val diff = if (filePaths.isEmpty()) {
                // 没有指定文件，自动判断 staged / unstaged
                val staged = com.codeforge.plugin.idea.review.ReviewService
                    .getChangedFiles(project).isNotEmpty()
                com.codeforge.plugin.idea.review.ReviewService.getDiff(
                    project,
                    emptyList(),
                    staged = staged
                )
            } else {
                com.codeforge.plugin.idea.review.ReviewService.getDiff(
                    project, filePaths, stagedRaw
                )
            }

            // 2. 通知前端 review 开始（显示 streaming UI）
            executeJS("window.onReviewStart && window.onReviewStart()")

            // 3. 调用 AI 审查
            com.codeforge.plugin.idea.review.ReviewService.reviewCode(
                diff = diff,
                projectName = project.name,
                onToken = { token ->
                    val b64 = java.util.Base64.getEncoder()
                        .encodeToString(token.toByteArray(Charsets.UTF_8))
                    executeJS("window.onReviewToken && window.onReviewToken('$b64')")
                },
                onDone = {
                    executeJS("window.onReviewDone && window.onReviewDone()")
                },
                onError = { ex ->
                    val msg = (ex.message ?: "审查失败").replace("\"", "\\\"").replace("\n", "\\n")
                    executeJS("window.onReviewError && window.onReviewError(\"$msg\")")
                }
            )
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 在 JCEF 浏览器中执行 JavaScript（线程安全）
     */
    fun executeJS(js: String) {
        browser?.cefBrowser?.let { cef ->
            ApplicationManager.getApplication().invokeLater {
                cef.executeJavaScript(js, cef.url ?: "", 0)
            }
        }
    }

    /**
     * JCEF 不可用时的降级 HTML
     */
    private fun buildFallbackHtml(): String = """
        <!DOCTYPE html><html><body style="background:#1e1e1e;color:#ccc;
        display:flex;align-items:center;justify-content:center;height:100vh;
        font-family:sans-serif;text-align:center;">
        <div>
            <h2>🔥 CodeForge</h2>
            <p>请在 Settings → CodeForge 中配置模型 API Key</p>
            <p style="color:#888;font-size:12px">支持 DeepSeek / GPT / Claude / Qwen 等 16+ 模型</p>
        </div>
        </body></html>
    """.trimIndent()
}

