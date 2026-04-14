package com.forgecode.plugin.idea.toolwindow

import com.forgecode.plugin.idea.service.BackendService
import com.forgecode.plugin.idea.settings.ForgeSettings
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
 * Forge Code 聊天主面板
 *
 * 使用 JCEF（内嵌 Chromium）加载 webui/index.html，
 * 通过 JBCefJSQuery 实现 JS ↔ Kotlin 双向通信。
 */
class ForgeChatPanel(private val project: Project) {

    private val log = logger<ForgeChatPanel>()
    private val gson = Gson()
    private val backendService = BackendService.getInstance()

    /** 根容器 */
    private val rootPanel = JPanel(BorderLayout())

    /** JCEF 浏览器实例（懒加载） */
    private var browser: JBCefBrowser? = null

    /** JS → Kotlin 消息通道 */
    private var jsQuery: JBCefJSQuery? = null

    /** 当前正在进行的 SSE EventSource（用于取消） */
    private var currentEventSource: okhttp3.sse.EventSource? = null

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
            console.log('[ForgeCode] JS Bridge 注入完成');
        """.trimIndent()
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
    }

    /**
     * 页面加载完成后，推送初始化数据（当前模型、设置等）
     */
    private fun loadInitialData() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val health = backendService.healthCheck()
            val models = backendService.getModels()
            val settings = ForgeSettings.getInstance()

            val initData = mapOf(
                "backendOnline" to (health != null),
                "activeProvider" to (health?.activeProvider ?: ""),
                "activeModel" to (health?.activeModel ?: ""),
                "models" to (models?.providers ?: emptyList<Any>()),
                "theme" to settings.theme,
                "language" to settings.language,
                "defaultMode" to settings.defaultMode,
                "projectName" to (project.name)
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
                "sendMessage" -> handleSendMessage(json)
                "cancelMessage" -> handleCancelMessage()
                "switchModel" -> handleSwitchModel(json)
                "getModels" -> handleGetModels()
                "newSession" -> handleNewSession()
                "applyCode" -> handleApplyCode(json)
                "openSettings" -> handleOpenSettings()
                else -> log.warn("未知 JS 消息类型: $type")
            }
        } catch (e: Exception) {
            log.error("处理 JS 消息失败: $request", e)
        }
    }

    /** 处理发送消息（流式对话） */
    private fun handleSendMessage(json: Map<*, *>) {
        val content = json["content"] as? String ?: return
        val sessionId = json["sessionId"] as? String

        // 取消上一个未完成的流
        currentEventSource?.cancel()

        val messages = listOf(
            BackendService.ChatMessage("user", content)
        )
        val request = BackendService.ChatRequest(
            sessionId = sessionId,
            messages = messages
        )

        // 通知 JS 开始流式输出
        executeJS("window.onStreamStart && window.onStreamStart()")

        currentEventSource = backendService.chat(
            chatRequest = request,
            onToken = { token ->
                // 转义反引号和反斜杠，避免 JS 语法错误
                val escaped = token
                    .replace("\\", "\\\\")
                    .replace("`", "\\`")
                    .replace("$", "\\$")
                executeJS("window.appendToken && window.appendToken(`$escaped`)")
            },
            onDone = {
                executeJS("window.onStreamDone && window.onStreamDone()")
            },
            onError = { ex ->
                val msg = (ex.message ?: "未知错误")
                    .replace("\"", "\\\"")
                executeJS("window.onError && window.onError(\"$msg\")")
            }
        )
    }

    /** 取消当前流式输出 */
    private fun handleCancelMessage() {
        currentEventSource?.cancel()
        currentEventSource = null
        executeJS("window.onStreamDone && window.onStreamDone()")
    }

    /** 切换模型 */
    private fun handleSwitchModel(json: Map<*, *>) {
        val provider = json["provider"] as? String ?: return
        val model = json["model"] as? String ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = backendService.switchModel(provider, model)
            if (result?.success == true) {
                executeJS(
                    "window.onModelSwitched && window.onModelSwitched(" +
                            "\"${result.activeProvider}\", \"${result.activeModel}\")"
                )
                // 通知状态栏刷新
                ApplicationManager.getApplication().invokeLater {
                    project.messageBus.syncPublisher(ModelChangedListener.TOPIC)
                        .onModelChanged(result.activeProvider ?: "", result.activeModel ?: "")
                }
            }
        }
    }

    /** 获取模型列表 */
    private fun handleGetModels() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val models = backendService.getModels()
            executeJS("window.updateModels && window.updateModels(${gson.toJson(models)})")
        }
    }

    /** 新建会话 */
    private fun handleNewSession() {
        executeJS("window.onNewSession && window.onNewSession()")
    }

    /** 将 AI 生成的代码应用到编辑器 */
    private fun handleApplyCode(json: Map<*, *>) {
        val code = json["code"] as? String ?: return
        val language = json["language"] as? String ?: ""
        ApplicationManager.getApplication().invokeLater {
            com.forgecode.plugin.idea.util.EditorUtil.applyCodeToEditor(project, code, language)
        }
    }

    /** 打开插件设置页 */
    private fun handleOpenSettings() {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "Forge Code")
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
            <h2>🔥 Forge Code</h2>
            <p>正在连接后端服务...</p>
            <p style="color:#888;font-size:12px">确保 claude-api-proxy 已启动</p>
        </div>
        </body></html>
    """.trimIndent()
}
