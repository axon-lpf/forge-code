package com.codeforge.plugin.idea.toolwindow

import com.codeforge.plugin.idea.service.LlmService
import com.codeforge.plugin.idea.service.SessionManager
import com.codeforge.plugin.idea.settings.CodeForgeSettings
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JFileChooser
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

    /**
     * P2-10：待发送的图片列表（用户粘贴后暂存，随下一条消息发出）
     * 格式：[{type: "image_url", image_url: {url: "data:image/...;base64,..."}}]
     */
    private val pendingImageParts = mutableListOf<Map<String, Any>>()

    /**
     * A3：待发送的文档列表（用户选择后暂存，随下一条消息发出）
     * 格式：[filename] 内容... [/filename]
     */
    private val pendingDocParts = mutableListOf<Pair<String, String>>()

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

            // T15：加载项目规则文件信息，用于 UI 显示激活标识
            val rulesInfo = com.codeforge.plugin.idea.util.ProjectRulesLoader.loadInfo(project)
            val rulesLabel = if (rulesInfo != null) {
                rulesInfo.second.let { sourcePath ->
                    val homeDir = System.getProperty("user.home") ?: ""
                    when {
                        sourcePath.contains(".codeforge/global.md") ->
                            "~/.codeforge/global.md（全局规则）"
                        sourcePath.endsWith(".codeforge.md") ->
                            ".codeforge.md（项目规则）"
                        else -> sourcePath
                    }
                }
            } else ""

            // P2-10：检测当前 Provider 是否支持 Vision
            val visionSupported = com.codeforge.plugin.llm.provider.ProviderRegistry
                .get(activeInfo.provider ?: "")?.supportsVision ?: false

            val initData = mapOf(
                "backendOnline" to true,
                "activeProvider" to (activeInfo.provider ?: ""),
                "activeModel" to (activeInfo.model ?: ""),
                "models" to providers,
                "theme" to settings.theme,
                "language" to settings.language,
                "defaultMode" to settings.defaultMode,
                "projectName" to (project.name),
                "userName" to sysUser,
                // T15：规则文件激活标识（空字符串表示无规则文件）
                "rulesLabel" to rulesLabel,
                // B1：一键生成 Rules — 是否有现有规则文件
                "hasRules" to (rulesInfo != null),
                // B6：Prompt 模板列表
                "promptTemplates" to com.codeforge.plugin.idea.settings.PromptTemplateManager.getAllTemplates()
                    .map { mapOf("id" to it.id, "name" to it.name, "icon" to it.icon, "desc" to it.prompt.take(60)) },
                // P2-10：Vision 支持标识
                "visionSupported" to visionSupported
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
                "clearSession"  -> handleClearSession()
                "openRules"    -> handleOpenRules()
                "openToolConfig" -> handleOpenToolConfig()
                "generateCommit" -> handleGenerateCommit()
                "loadSession"   -> handleLoadSession(json)
                "deleteSession" -> handleDeleteSession(json)
                "renameSession" -> handleRenameSession(json)
                "getSessions"   -> handleGetSessions()
                "exportSession" -> handleExportSession(json)
                "searchFiles"    -> handleSearchFiles(json)
                "readFileCtx"    -> handleReadFileCtx(json)
                "applyCode"      -> handleApplyCode(json)
                "openSettings"   -> handleOpenSettings()
                "runAgent"          -> handleRunAgent(json)
                "generatePlan"      -> handleGeneratePlan(json)
                "executePlan"       -> handleExecutePlan(json)
                "getChangedFiles"   -> handleGetChangedFiles()
                "reviewCode"        -> handleReviewCode(json)
                // T13：Agent 步骤可视化 Bridge 消息类型（JS 侧主动触发，当前为预留接口）
                "AGENT_STEP_TOKEN"  -> { /* JS 侧发送 thinking token，当前由 Kotlin push 实现，此处 no-op */ }
                "AGENT_TOOL_CARD"   -> { /* JS 侧发送工具卡片，当前由 Kotlin push 实现，此处 no-op */ }
                // P2-9：Checkpoint 回滚
                "rollbackCheckpoint" -> handleRollbackCheckpoint(json)
                "getCheckpoints"     -> handleGetCheckpoints()
                // P2-10：多模态图片输入
                "imageInput"         -> handleImageInput(json)
                // A2/A3：+ 号菜单触发文件选择
                "openImagePicker"    -> handleOpenImagePicker()
                "openDocumentPicker" -> handleOpenDocumentPicker()
                // B1：一键生成规则文件
                "generateRules"      -> handleGenerateRules()
                // B3：需求澄清答案
                "clarifyAnswer"      -> handleClarifyAnswer(json)
                // B5：消息反馈 + 重新生成
                "regenerate"         -> handleRegenerate(json)
                "feedback"           -> handleFeedback(json)
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

        // 保存用户消息（纯文本，会话历史中只存文本）
        SessionManager.appendMessage(sid, "user", content)

        // 构建完整历史消息（含本次）
        val historyMessages = SessionManager.getMessages(sid)

        // P2-10：如有待发送图片，将最后一条 user 消息的 content 替换为多模态数组
        val imageParts = synchronized(pendingImageParts) {
            val copy = pendingImageParts.toList()
            pendingImageParts.clear()
            copy
        }
        // A3：待发送文档内容
        val docParts = synchronized(pendingDocParts) {
            val copy = pendingDocParts.toList()
            pendingDocParts.clear()
            copy
        }

        // 组装文档附加文本
        val docBlock = if (docParts.isNotEmpty()) {
            docParts.joinToString("\n") { (name, text) ->
                "[附件: $name]\n$text\n[/附件]"
            } + "\n\n"
        } else {
            ""
        }

        val messages: List<Map<String, Any>> = when {
            imageParts.isNotEmpty() -> {
                val contentParts = mutableListOf<Map<String, Any>>()
                if ((docBlock + content).isNotBlank()) {
                    contentParts.add(mapOf("type" to "text", "text" to docBlock + content))
                }
                contentParts.addAll(imageParts)
                val withImage = historyMessages.dropLast(1).toMutableList()
                withImage.add(mapOf("role" to "user", "content" to contentParts))
                withImage
            }
            docParts.isNotEmpty() -> {
                // 纯文档附加，无图片
                val withDoc = historyMessages.dropLast(1).toMutableList()
                withDoc.add(mapOf("role" to "user", "content" to docBlock + content))
                withDoc
            }
            else -> historyMessages
        }

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
                executeJS("window.onStreamDone && window.onStreamDone()")
                // T20：首条消息后异步 LLM 生成 3~5 字会话标题
                val session = SessionManager.loadSession(sid)
                if (session != null && session.title == session.messages
                        .firstOrNull { it.role == "user" }?.content?.take(30)?.replace("\n", " ")) {
                    generateSessionTitle(sid, content)
                } else {
                    val summary = SessionManager.listSessions().find { it.id == sid }
                    if (summary != null) {
                        val titleEscaped = summary.title.replace("\"", "\\\"")
                        executeJS("window.onSessionTitleUpdate && window.onSessionTitleUpdate(\"$sid\", \"$titleEscaped\")")
                    }
                }
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
            },
            onUsage = { promptTokens, completionTokens ->
                executeJS("window.onTokenUsage && window.onTokenUsage($promptTokens, $completionTokens)")
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
                // P2-10：切换模型后推送 Vision 支持状态
                val newVisionSupported = com.codeforge.plugin.llm.provider.ProviderRegistry
                    .get(activeInfo.provider ?: "")?.supportsVision ?: false
                executeJS(
                    "window.onModelSwitched && window.onModelSwitched(" +
                            "\"${activeInfo.provider}\", \"${activeInfo.model}\", $newVisionSupported)"
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

    /** 新建会话（T20：创建后执行 FIFO 清理，保持 ≤50 条） */
    private fun handleNewSession() {
        val activeInfo = llmService.getActiveInfo()
        val session = SessionManager.createSession(
            provider = activeInfo.provider ?: "",
            model = activeInfo.model ?: ""
        )
        currentSessionId = session.id
        currentAiBuffer.clear()
        // T20：FIFO 清理，超过 50 条时删除最旧的
        ApplicationManager.getApplication().executeOnPooledThread {
            SessionManager.pruneOldSessions(maxCount = 50)
        }
        executeJS("window.onNewSession && window.onNewSession(${gson.toJson(mapOf("sessionId" to session.id))})")
    }

    /** A4：清空当前会话消息 */
    private fun handleClearSession() {
        val sid = currentSessionId ?: return
        SessionManager.clearSession(sid)
        currentAiBuffer.clear()
        executeJS("window.onSessionCleared && window.onSessionCleared()")
    }

    /** A4：打开规则文件编辑器 */
    private fun handleOpenRules() {
        executeJS("window.onRulesRequested && window.onRulesRequested()")
    }

    /** B1：一键生成规则文件 */
    private fun handleGenerateRules() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val buffer = StringBuilder()
            executeJS("window.onRulesGenerationStart && window.onRulesGenerationStart()")

            com.codeforge.plugin.idea.util.ProjectRulesLoader.generateRules(
                project = project,
                onDone = { generatedRules ->
                    // 写入项目根目录的 .codeforge.md
                    val projectPath = project.basePath
                    if (projectPath != null) {
                        val rulesFile = java.io.File(projectPath, ".codeforge.md")
                        try {
                            rulesFile.writeText(generatedRules, Charsets.UTF_8)
                            com.codeforge.plugin.idea.util.ProjectRulesLoader.invalidate(projectPath)
                            executeJS("window.onRulesGenerated && window.onRulesGenerated()")
                            log.info("B1: 规则文件已生成并保存: ${rulesFile.absolutePath}")
                        } catch (e: Exception) {
                            log.error("B1: 保存规则文件失败", e)
                            executeJS("window.onRulesGenerationError && window.onRulesGenerationError('保存失败: ${e.message}')")
                        }
                    }
                },
                onError = { ex ->
                    executeJS("window.onRulesGenerationError && window.onRulesGenerationError('${ex.message ?: "生成失败"}')")
                }
            )
        }
    }

    /** A5：打开工具配置面板 */
    private fun handleOpenToolConfig() {
        ApplicationManager.getApplication().invokeLater {
            ToolConfigPanel.show(project)
        }
    }

    /** B3：接收需求澄清答案 */
    private fun handleClarifyAnswer(json: Map<*, *>) {
        val answer = json["answer"] as? String ?: ""
        com.codeforge.plugin.idea.agent.AgentService.pendingClarifyAnswer = answer
    }

    /** B5：重新生成上一条回复 */
    private fun handleRegenerate(json: Map<*, *>) {
        val sid = currentSessionId ?: return
        val messages = SessionManager.getMessages(sid)
        if (messages.size < 2) return

        // 找到最后一条 user 消息，重新发送
        val lastUserMsg = messages.filter { (it["role"] as? String) == "user" }.lastOrNull() ?: return
        val userContent = lastUserMsg["content"] as? String ?: ""

        // 删除最后一条 assistant 消息
        val updatedMessages = messages.dropLast(1).filter { !(it["role"] == "assistant" && it == messages.last()) }
        // 重新调用 chatStream
        currentEventSource?.cancel()
        currentAiBuffer.clear()

        llmService.chatStream(
            messages = updatedMessages.map { mapOf("role" to (it["role"] as? String ?: ""), "content" to (it["content"] as? String ?: "")) },
            onToken = { token ->
                currentAiBuffer.append(token)
                val escaped = token.replace("\\","\\\\").replace("`","\\`").replace("$","\\$")
                executeJS("window.appendToken && window.appendToken(`$escaped`)")
            },
            onDone = {
                val aiContent = currentAiBuffer.toString()
                if (aiContent.isNotBlank()) {
                    SessionManager.appendMessage(sid, "assistant", aiContent)
                }
                currentAiBuffer.clear()
                executeJS("window.onStreamDone && window.onStreamDone()")
            },
            onError = { ex ->
                val msg = (ex.message ?: "未知错误").replace("\"","\\\"").replace("\n","\\n")
                executeJS("window.onError && window.onError(\"$msg\")")
            },
            onAutoRetry = { failedProvider, newProvider, newModel ->
                val failedDisplay = failedProvider.replace("\"", "\\\"")
                val newDisplay = newProvider.replace("\"", "\\\"")
                val modelDisplay = newModel.replace("\"", "\\\"")
                executeJS("window.onAutoRetry && window.onAutoRetry(\"$failedDisplay\", \"$newDisplay\", \"$modelDisplay\")")
            },
            onUsage = { promptTokens, completionTokens ->
                executeJS("window.onTokenUsage && window.onTokenUsage($promptTokens, $completionTokens)")
            }
        )
    }

    /** B5：记录消息反馈 */
    private fun handleFeedback(json: Map<*, *>) {
        val type = json["feedbackType"] as? String ?: return
        val reason = json["reason"] as? String
        val sid = currentSessionId ?: return
        log.info("B5: 收到反馈 type=$type reason=$reason session=$sid")
        // 反馈数据可后续写入 ForgeSettings 或发送至分析服务
    }

    /** A4：生成 Commit Message */
    private fun handleGenerateCommit() {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "请使用工具栏「📝 Commit」按钮生成 Commit Message。\n\n或者在 Git 面板中右键选择「Generate Commit Message」。",
                "Generate Commit Message"
            )
        }
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

    /**
     * T20：异步调用 LLM 为会话生成 3~5 字标题
     * 仅在首条 AI 回复完成后触发一次
     *
     * @param sessionId  会话 ID
     * @param userMsg    用户的第一条消息（用于生成标题的上下文）
     */
    private fun generateSessionTitle(sessionId: String, userMsg: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val prompt = "请为以下对话内容生成一个简洁的标题，3到5个中文字，不加任何标点或说明，只输出标题本身：\n\n${userMsg.take(200)}"
            val messages = listOf(mapOf("role" to "user", "content" to prompt))
            val titleBuf = StringBuilder()
            try {
                llmService.chatStream(
                    messages = messages,
                    onToken = { token -> titleBuf.append(token) },
                    onDone = {
                        val title = titleBuf.toString()
                            .trim()
                            .replace("\n", "")
                            .take(20)
                            .ifBlank { "新会话" }
                        SessionManager.renameSession(sessionId, title)
                        val escaped = title.replace("\"", "\\\"")
                        executeJS("window.onSessionTitleUpdate && window.onSessionTitleUpdate(\"$sessionId\", \"$escaped\")")
                    },
                    onError = { ex ->
                        log.warn("会话标题生成失败: ${ex.message}")
                        // 降级：保留首条消息截取的标题，直接推送
                        val summary = SessionManager.listSessions().find { it.id == sessionId }
                        if (summary != null) {
                            val escaped = summary.title.replace("\"", "\\\"")
                            executeJS("window.onSessionTitleUpdate && window.onSessionTitleUpdate(\"$sessionId\", \"$escaped\")")
                        }
                    }
                )
            } catch (ex: Exception) {
                log.warn("generateSessionTitle 异常: ${ex.message}")
            }
        }
    }

    /**
     * T21：导出会话为 Markdown 文件
     * 将会话消息格式化为 Markdown 并写入用户 Downloads 目录
     */
    private fun handleExportSession(json: Map<*, *>) {
        val sessionId = json["sessionId"] as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val session = SessionManager.loadSession(sessionId) ?: run {
                log.warn("导出失败：找不到会话 $sessionId")
                return@executeOnPooledThread
            }

            // 生成 Markdown 内容
            val sb = StringBuilder()
            sb.appendLine("# ${session.title}")
            sb.appendLine()
            sb.appendLine("> 导出时间：${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
            if (session.model.isNotBlank()) {
                sb.appendLine("> 模型：${session.provider} · ${session.model}")
            }
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()

            session.messages.forEach { msg ->
                when (msg.role) {
                    "user" -> {
                        sb.appendLine("## 👤 用户")
                        sb.appendLine()
                        sb.appendLine(msg.content)
                        sb.appendLine()
                    }
                    "assistant" -> {
                        sb.appendLine("## 🔥 CodeForge")
                        sb.appendLine()
                        sb.appendLine(msg.content)
                        sb.appendLine()
                    }
                }
                sb.appendLine("---")
                sb.appendLine()
            }

            // 写入文件：~/Downloads/codeforge-{title}-{timestamp}.md
            val safeTitle = session.title
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(30)
            val timestamp = System.currentTimeMillis()
            val downloadsDir = java.io.File(System.getProperty("user.home"), "Downloads")
                .also { it.mkdirs() }
            val outFile = java.io.File(downloadsDir, "codeforge-$safeTitle-$timestamp.md")

            try {
                outFile.writeText(sb.toString(), Charsets.UTF_8)
                val pathEscaped = outFile.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
                executeJS("window.onExportDone && window.onExportDone({filePath: \"$pathEscaped\"})")
                log.info("会话已导出：${outFile.absolutePath}")
            } catch (ex: Exception) {
                log.error("导出会话失败: ${ex.message}", ex)
            }
        }
    }

    /**
     * T24：搜索项目文件/类/方法（@ 引用触发）
     * 优先调用 CodebaseSearcher PSI 语义搜索，降级为 FileContextProvider grep 搜索
     */
    private fun handleSearchFiles(json: Map<*, *>) {
        val keyword = json["keyword"] as? String ?: ""
        ApplicationManager.getApplication().executeOnPooledThread {
            // 优先 PSI 语义搜索（类名/文件名/方法名）
            val psiResults = if (keyword.isNotBlank()) {
                com.codeforge.plugin.idea.util.CodebaseSearcher
                    .searchByName(project, keyword, limit = 20)
                    .map { r ->
                        mapOf(
                            "path" to r.filePath,
                            "name" to r.filePath.substringAfterLast('/').substringAfterLast('\\'),
                            "display" to r.displayText,
                            "kind" to r.kind.name
                        )
                    }
            } else emptyList()

            if (psiResults.isNotEmpty()) {
                executeJS("window.onFileSearchResult && window.onFileSearchResult(${gson.toJson(psiResults)})")
                return@executeOnPooledThread
            }

            // 降级：原有 FileContextProvider 文件 grep 搜索
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

        // P2-10/A3：收集附件
        val imageParts = synchronized(pendingImageParts) {
            val copy = pendingImageParts.toList()
            pendingImageParts.clear()
            copy
        }
        val docParts = synchronized(pendingDocParts) {
            val copy = pendingDocParts.toList()
            pendingDocParts.clear()
            copy
        }
        val docBlock = if (docParts.isNotEmpty()) {
            docParts.joinToString("\n") { (name, text) ->
                "[附件: $name]\n$text\n[/附件]"
            } + "\n\n"
        } else {
            ""
        }

        val content = if (editorContext.isNotBlank()) {
            "$docBlock$rawContent\n$editorContext"
        } else {
            "$docBlock$rawContent"
        }

        val sid = currentSessionId ?: run {
            val activeInfo = llmService.getActiveInfo()
            val s = SessionManager.createSession(activeInfo.provider ?: "", activeInfo.model ?: "")
            currentSessionId = s.id; s.id
        }
        SessionManager.appendMessage(sid, "user", content)

        // 构建消息列表（带图片多模态）
        val history = SessionManager.getMessages(sid)
        val messages: List<Map<String, Any>> = if (imageParts.isNotEmpty()) {
            val contentParts = mutableListOf<Map<String, Any>>()
            if (content.isNotBlank()) {
                contentParts.add(mapOf("type" to "text", "text" to content))
            }
            contentParts.addAll(imageParts)
            val withImage = history.dropLast(1).toMutableList()
            withImage.add(mapOf("role" to "user", "content" to contentParts))
            withImage
        } else {
            history
        }

        currentAiBuffer.clear()

        com.codeforge.plugin.idea.agent.AgentService.runAgent(
            project = project,
            userMessage = content,
            sessionHistory = messages,
            onCheckpointCreated = { checkpoints ->
                // P2-9：将最新 Checkpoint 列表推送到 UI 时间线
                val cpJson = gson.toJson(checkpoints)
                executeJS("window.onCheckpointList && window.onCheckpointList($cpJson)")
            },
            // B3：需求澄清
            onClarifyNeeded = { questions ->
                val json = gson.toJson(questions)
                executeJS("window.renderClarifyCard && window.renderClarifyCard($json)")
            },
            onStep = { step ->
                // toolResult 可能含有文件内容（反引号、$、\），需要 Base64 编码避免 JS 注入问题
                val safeResult = java.util.Base64.getEncoder()
                    .encodeToString((step.toolResult ?: "").toByteArray(Charsets.UTF_8))
                // T10：toolInput 参数摘要同样 Base64 编码，安全传递
                val safeInput = java.util.Base64.getEncoder()
                    .encodeToString((step.toolInput ?: "").toByteArray(Charsets.UTF_8))
                val stepJson = gson.toJson(mapOf(
                    "type" to step.type.name,
                    "content" to step.content,
                    "toolName" to (step.toolName ?: ""),
                    "toolResultB64" to safeResult,   // Base64 编码的工具结果
                    "toolInputB64" to safeInput       // T10：Base64 编码的工具参数摘要
                ))
                executeJS("window.onAgentStep && window.onAgentStep($stepJson)")
            },
            // T10：THINKING 阶段实时 token → 调用 window.appendThinkingToken
            onThinkingToken = { token ->
                val b64 = java.util.Base64.getEncoder()
                    .encodeToString(token.toByteArray(Charsets.UTF_8))
                executeJS("window.appendThinkingToken && window.appendThinkingToken('$b64')")
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
                // T13：确保 Agent 面板在任务完成时切换为 done 状态
                executeJS("window.finalizeAgentPanel && window.finalizeAgentPanel()")
                executeJS("window.onStreamDone && window.onStreamDone()")
            },
            onError = { ex ->
                currentAiBuffer.clear()
                // T13：出错时也关闭 Agent 面板
                executeJS("window.finalizeAgentPanel && window.finalizeAgentPanel()")
                val msg = (ex.message ?: "未知错误").replace("\"","\\\"").replace("\n","\\n")
                executeJS("window.onError && window.onError(\"$msg\")")
            }
        )
    }

    /** Spec 模式：生成执行计划 */
    private fun handleGeneratePlan(json: Map<*, *>) {
        val rawContent = json["content"] as? String ?: return

        // P2-10/A3：收集附件
        val imageParts = synchronized(pendingImageParts) {
            val copy = pendingImageParts.toList()
            pendingImageParts.clear()
            copy
        }
        val docParts = synchronized(pendingDocParts) {
            val copy = pendingDocParts.toList()
            pendingDocParts.clear()
            copy
        }
        val docBlock = if (docParts.isNotEmpty()) {
            docParts.joinToString("\n") { (name, text) ->
                "[附件: $name]\n$text\n[/附件]"
            } + "\n\n"
        } else {
            ""
        }
        val content = docBlock + rawContent

        val sid = currentSessionId ?: run {
            val activeInfo = llmService.getActiveInfo()
            val s = SessionManager.createSession(activeInfo.provider ?: "", activeInfo.model ?: "")
            currentSessionId = s.id; s.id
        }
        SessionManager.appendMessage(sid, "user", content)
        val history = SessionManager.getMessages(sid)
        val messages: List<Map<String, Any>> = if (imageParts.isNotEmpty()) {
            val contentParts = mutableListOf<Map<String, Any>>()
            if (content.isNotBlank()) {
                contentParts.add(mapOf("type" to "text", "text" to content))
            }
            contentParts.addAll(imageParts)
            val withImage = history.dropLast(1).toMutableList()
            withImage.add(mapOf("role" to "user", "content" to contentParts))
            withImage
        } else {
            history
        }
        currentAiBuffer.clear()

        com.codeforge.plugin.idea.agent.AgentService.generatePlan(
            userRequirement = content,
            sessionHistory = messages,
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

    // ==================== P2-9：Checkpoint 回滚 ====================

    /**
     * 执行 Checkpoint 回滚
     * JS 发送: {type: "rollbackCheckpoint", checkpointId: "xxx"}
     */
    private fun handleRollbackCheckpoint(json: Map<*, *>) {
        val checkpointId = json["checkpointId"] as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val success = com.codeforge.plugin.idea.agent.CheckpointManager.restore(project, checkpointId)
            if (success) {
                executeJS("window.onRollbackDone && window.onRollbackDone({checkpointId: \"$checkpointId\", success: true})")
                log.info("P2-9: Checkpoint [$checkpointId] 回滚成功")
            } else {
                executeJS("window.onRollbackDone && window.onRollbackDone({checkpointId: \"$checkpointId\", success: false})")
                log.warn("P2-9: Checkpoint [$checkpointId] 回滚失败")
            }
        }
    }

    /**
     * 获取全部 Checkpoint 列表（JS 请求时主动拉取）
     */
    private fun handleGetCheckpoints() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val checkpoints = com.codeforge.plugin.idea.agent.CheckpointManager.getSummaryList()
            val cpJson = gson.toJson(checkpoints)
            executeJS("window.onCheckpointList && window.onCheckpointList($cpJson)")
        }
    }

    // ==================== P2-10：多模态图片输入 ====================

    /**
     * 接收 JS 侧传来的 base64 图片，暂存到 pendingImageParts
     * 等下一条 sendMessage 时拼入消息 content 数组
     *
     * 消息格式符合 OpenAI Vision API：
     * {type: "image_url", image_url: {url: "data:image/png;base64,..."}}
     */
    private fun handleImageInput(json: Map<*, *>) {
        val base64 = json["base64"] as? String ?: return
        val mimeType = (json["mimeType"] as? String)?.takeIf { it.isNotBlank() } ?: "image/png"
        val dataUrl = "data:$mimeType;base64,$base64"
        val imagePart = mapOf(
            "type" to "image_url",
            "image_url" to mapOf("url" to dataUrl)
        )
        synchronized(pendingImageParts) {
            pendingImageParts.add(imagePart)
        }
log.info("P2-10: 收到图片输入，mimeType=$mimeType，size=${base64.length} chars (base64)")
    }

    /** A2：+ 号菜单点击"图片"，弹出系统文件选择框 */
    private fun handleOpenImagePicker() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        descriptor.title = "选择图片"
        descriptor.description = "选择要附加的图片文件（PNG/JPG/GIF/WebP）"
        FileChooser.chooseFiles(descriptor, project, null) { files ->
            for (file in files) {
                val path = file.path
                if (!file.isValid) continue
                val bytes = file.inputStream.use { it.readBytes() }
                val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                val mimeType = when {
                    path.endsWith(".png", ignoreCase = true) -> "image/png"
                    path.endsWith(".gif", ignoreCase = true) -> "image/gif"
                    path.endsWith(".webp", ignoreCase = true) -> "image/webp"
                    path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    else -> "image/png"
                }
                val dataUrl = "data:$mimeType;base64,$base64"
                val imagePart = mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to dataUrl)
                )
                synchronized(pendingImageParts) {
                    pendingImageParts.add(imagePart)
                }
                executeJS("window.addImagePreview && window.addImagePreview('${base64.replace("'", "\\'")}', '$mimeType', '${dataUrl.replace("'", "\\'")}')")
            }
        }
    }

    /** A3：+ 号菜单点击"文档"，弹出系统文件选择框 */
    private fun handleOpenDocumentPicker() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        descriptor.title = "选择文档"
        descriptor.description = "选择要附加的文档文件（MD/TXT/PDF/代码文件）"
        FileChooser.chooseFiles(descriptor, project, null) { files ->
            for (file in files) {
                val path = file.path
                if (!file.isValid) continue
                val fileName = file.name
                val result = com.codeforge.plugin.idea.util.DocumentExtractor.extract(path)
                if (result.success && result.content != null) {
                    synchronized(pendingDocParts) {
                        pendingDocParts.add(fileName to result.content)
                    }
                    // 通知 JS 显示文档预览标签
                    executeJS("window.addDocumentPreview && window.addDocumentPreview('$fileName')")
                } else {
                    log.warn("A3: 文档提取失败: $path, error: ${result.error}")
                }
            }
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

