package com.forgecode.plugin.idea.service

import com.forgecode.plugin.llm.ProviderManager
import com.forgecode.plugin.llm.dto.ChatRequest
import com.forgecode.plugin.llm.provider.LlmProvider
import com.forgecode.plugin.llm.provider.ProviderRegistry
import com.forgecode.plugin.idea.settings.ForgeSettings
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger

/**
 * LLM 服务 — 应用级单例
 *
 * 替代旧的 BackendService，不再通过 HTTP 调外部后端，
 * 而是直接调用内置的 ProviderManager（Java 层）。
 *
 * 对外接口保持简洁，供 ForgeChatPanel / StatusBar 等组件调用。
 */
@Service(Service.Level.APP)
class LlmService {

    private val log = logger<LlmService>()
    private val providerManager = ProviderManager()

    @Volatile
    private var initialized = false

    // ==================== 数据模型 ====================

    data class ActiveInfo(
        val provider: String?,
        val model: String?,
        val displayName: String?
    )

    data class ProviderInfo(
        val name: String,
        val displayName: String,
        val region: String,
        val enabled: Boolean,
        val hasApiKey: Boolean,
        val currentModel: String?,
        val models: List<String>,
        val description: String?
    )

    data class TestResult(
        val success: Boolean,
        val provider: String,
        val model: String?,
        val responseTime: Long,
        val response: String?,
        val error: String?
    )

    // ==================== 初始化 ====================

    /**
     * 确保 Provider 层已初始化（懒加载，首次调用时执行）
     */
    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        val settings = ForgeSettings.getInstance()
        val configs = settings.getProviderConfigMap()

        providerManager.init(
            configs,
            settings.activeProvider,
            settings.activeModel
        )
        initialized = true
        log.info("LlmService 初始化完成，已加载 ${providerManager.allProviders.size} 个提供商")
    }

    // ==================== 查询 ====================

    /**
     * 获取当前激活的 Provider 和 Model 信息
     */
    fun getActiveInfo(): ActiveInfo {
        ensureInitialized()
        val provider = providerManager.activeProvider
        return ActiveInfo(
            provider = providerManager.activeProviderName ?: "",
            model = provider?.currentModel ?: providerManager.activeModelName ?: "",
            displayName = provider?.displayName
        )
    }

    /**
     * 获取所有 Provider 列表（供 UI 展示）
     *
     * 包含所有内置提供商（含未配置的），按 ProviderRegistry 顺序排列
     */
    fun getProviderList(): List<ProviderInfo> {
        ensureInitialized()
        val settings = ForgeSettings.getInstance()
        val userConfigs = settings.getProviderConfigMap()
        val activeProviders = providerManager.allProviders

        return ProviderRegistry.getAll().map { (name, meta) ->
            val userConfig = userConfigs[name]
            val liveProvider = activeProviders[name]

            ProviderInfo(
                name = name,
                displayName = meta.displayName(),
                region = meta.region(),
                enabled = liveProvider != null,
                hasApiKey = !userConfig?.apiKey.isNullOrBlank(),
                currentModel = liveProvider?.currentModel ?: userConfig?.currentModel ?: meta.defaultModel(),
                models = meta.models(),
                description = meta.description()
            )
        }
    }

    // ==================== 模型切换 ====================

    /**
     * 切换激活的提供商和模型
     *
     * @return true=切换成功
     */
    fun switchModel(provider: String, model: String): Boolean {
        ensureInitialized()
        return try {
            providerManager.switchProvider(provider, model)
            // 持久化到设置
            val settings = ForgeSettings.getInstance()
            settings.activeProvider = provider
            settings.activeModel = model
            log.info("模型已切换: $provider / $model")
            true
        } catch (e: Exception) {
            log.warn("切换模型失败: ${e.message}")
            false
        }
    }

    // ==================== 流式对话 ====================

    /**
     * 流式对话 — 支持自动重试
     *
     * @param messages     消息列表
     * @param onToken      收到一个 token 时的回调
     * @param onDone       流式结束时的回调
     * @param onError      出错时的回调（包含错误信息 + 备选模型列表）
     * @param onAutoRetry  自动切换模型重试时的通知（返回切换到的 provider 和 model）
     */
    fun chatStream(
        messages: List<Map<String, Any>>,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit,
        onAutoRetry: ((failedProvider: String, newProvider: String, newModel: String) -> Unit)? = null
    ) {
        ensureInitialized()

        val provider = providerManager.activeProvider
        if (provider == null) {
            onError(IllegalStateException("未配置任何可用的模型提供商，请在 Settings → Forge Code 中配置 API Key"))
            return
        }

        val request = buildChatRequest(messages)

        // 防止 onDone 被 [DONE] 事件和 onClosed 双重触发
        val doneCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val safeOnDone = {
            if (doneCalled.compareAndSet(false, true)) onDone()
        }

        // 调用 Provider 流式接口
        provider.chatCompletionStream(
            request,
            { data ->  // onEvent
                if (data == "[DONE]") {
                    safeOnDone()
                    return@chatCompletionStream
                }
                try {
                    val token = parseTokenFromChunk(data)
                    if (token != null) onToken(token)
                } catch (e: Exception) {
                    log.debug("解析 SSE chunk 失败: $data")
                }
            },
            { safeOnDone() },   // onComplete（onClosed）
            { error ->          // onError: 自动重试一次
                val failedName = provider.name
                val failedError = error?.message ?: "未知错误"
                log.warn("[$failedName] 流式对话失败: $failedError", error)

                val fallback = getNextAvailableProvider(failedName)
                if (fallback != null) {
                    log.info("自动切换到备选模型: [${fallback.name}] ${fallback.currentModel}")
                    providerManager.switchProvider(fallback.name, fallback.currentModel)
                    onAutoRetry?.invoke(failedName, fallback.name, fallback.currentModel)

                    val retryRequest = buildChatRequest(messages)
                    val retryDoneCalled = java.util.concurrent.atomic.AtomicBoolean(false)
                    val safeRetryDone = { if (retryDoneCalled.compareAndSet(false, true)) onDone() }
                    fallback.chatCompletionStream(
                        retryRequest,
                        { data ->
                            if (data == "[DONE]") { safeRetryDone(); return@chatCompletionStream }
                            try {
                                val token = parseTokenFromChunk(data)
                                if (token != null) onToken(token)
                            } catch (_: Exception) {}
                        },
                        { safeRetryDone() },
                        { retryError ->
                            log.error("[${fallback.name}] 重试也失败: ${retryError?.message}", retryError)
                            onError(Exception(
                                "[$failedName] $failedError\n[${fallback.name}] 重试失败: ${retryError?.message}"
                            ))
                        }
                    )
                } else {
                    onError(Exception("[$failedName] $failedError"))
                }
            }
        )
    }

    /**
     * 非流式同步调用（专供 MiniMax 等不支持真正 SSE 的模型）
     * 在后台线程执行，完成后模拟 token 逐块推送产生打字效果
     */
    private fun doNonStreamCall(
        provider: com.forgecode.plugin.llm.provider.LlmProvider,
        messages: List<Map<String, Any>>,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        Thread {
            try {
                val request = buildChatRequest(messages)
                request.stream = false
                val response = provider.chatCompletion(request)
                val content = response?.choices?.firstOrNull()?.message?.content
                if (content.isNullOrBlank()) {
                    onError(Exception("${provider.displayName} 返回空响应，请检查 API Key 是否正确"))
                    return@Thread
                }
                // 分块推送，产生打字效果
                content.chunked(8).forEach { chunk ->
                    onToken(chunk)
                    Thread.sleep(15)
                }
                onDone()
            } catch (e: Exception) {
                log.error("[${provider.name}] 非流式调用失败: ${e.message}", e)
                onError(e)
            }
        }.start()
    }

    /**
     * 获取下一个可用的 Provider（排除当前失败的）
     */
    private fun getNextAvailableProvider(excludeName: String): com.forgecode.plugin.llm.provider.LlmProvider? {
        return providerManager.allProviders.values
            .firstOrNull { it.name != excludeName }
    }

    /**
     * 获取所有备选 Provider 信息（供 UI 展示切换按钮）
     */
    fun getAlternativeProviders(excludeName: String): List<ProviderInfo> {
        ensureInitialized()
        return providerManager.allProviders
            .filter { it.key != excludeName }
            .map { (name, provider) ->
                val meta = ProviderRegistry.get(name)
                ProviderInfo(
                    name = name,
                    displayName = meta?.displayName() ?: name,
                    region = meta?.region() ?: "global",
                    enabled = true,
                    hasApiKey = true,
                    currentModel = provider.currentModel,
                    models = provider.availableModels,
                    description = meta?.description()
                )
            }
    }

    /**
     * 构建 ChatRequest
     */
    private fun buildChatRequest(messages: List<Map<String, Any>>): ChatRequest {
        val request = ChatRequest()
        val chatMessages = messages.map { msg ->
            val m = ChatRequest.ChatMessage()
            m.role = msg["role"] as? String ?: "user"
            m.content = msg["content"] ?: ""
            m
        }
        request.messages = chatMessages
        request.stream = true
        return request
    }

    // ==================== 测试连通性 ====================

    /**
     * 测试某个提供商的连通性
     *
     * 发送一条简单消息，检查能否正常回复
     */
    fun testProvider(providerName: String, apiKey: String, baseUrl: String?,
                     currentModel: String? = null): TestResult {
        val meta = ProviderRegistry.get(providerName)

        // 临时创建一个 Provider 进行测试
        val tempConfig = ProviderManager.ProviderConfig(apiKey)
        tempConfig.baseUrl = baseUrl
        // 优先用用户指定的模型（如豆包的 ep-xxx），否则用默认
        tempConfig.currentModel = currentModel?.takeIf { it.isNotBlank() } ?: meta?.defaultModel()
        tempConfig.connectTimeout = 15
        tempConfig.readTimeout = 30

        val tempManager = ProviderManager()
        tempManager.init(
            mapOf(providerName to tempConfig),
            providerName,
            meta?.defaultModel() ?: ""
        )

        val provider = tempManager.activeProvider
            ?: return TestResult(false, providerName, null, 0, null, "无法创建 Provider 实例")

        return try {
            val request = ChatRequest()
            val msg = ChatRequest.ChatMessage("user", "Hi, reply with just 'OK'")
            request.messages = listOf(msg)
            request.stream = false
            request.maxTokens = 10

            val startTime = System.currentTimeMillis()
            val response = provider.chatCompletion(request)
            val elapsed = System.currentTimeMillis() - startTime

            val content = response?.choices?.firstOrNull()?.message?.content ?: ""

            TestResult(
                success = true,
                provider = providerName,
                model = provider.currentModel,
                responseTime = elapsed,
                response = content,
                error = null
            )
        } catch (e: Exception) {
            TestResult(
                success = false,
                provider = providerName,
                model = provider.currentModel,
                responseTime = 0,
                response = null,
                error = e.message ?: "未知错误"
            )
        }
    }

    // ==================== 配置变更 ====================

    /**
     * 重新加载单个 Provider（配置变更后调用）
     */
    fun reloadProvider(name: String, config: ProviderManager.ProviderConfig) {
        ensureInitialized()
        providerManager.reloadProvider(name, config)
    }

    /**
     * 重新初始化所有 Provider（全量刷新）
     */
    fun reinitialize() {
        initialized = false
        ensureInitialized()
    }

    // ==================== 工具方法 ====================

    /**
     * 从 OpenAI SSE chunk 中提取 delta content
     *
     * chunk 格式: {"choices":[{"delta":{"content":"hello"},"index":0}]}
     */
    private fun parseTokenFromChunk(data: String): String? {
        return try {
            val json = JsonParser.parseString(data).asJsonObject
            val choices = json.getAsJsonArray("choices") ?: return null
            if (choices.isEmpty) return null
            val choice = choices[0].asJsonObject

            // 优先取流式的 delta.content
            val delta = choice.getAsJsonObject("delta")
            if (delta != null) {
                val content = delta.get("content")
                if (content != null && !content.isJsonNull) return content.asString
            }

            // 非流式（如 MiniMax stream=false）取 message.content
            val message = choice.getAsJsonObject("message")
            if (message != null) {
                val content = message.get("content")
                if (content != null && !content.isJsonNull) return content.asString
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun getInstance(): LlmService = service()
    }
}