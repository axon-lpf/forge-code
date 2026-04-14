package com.forgecode.plugin.idea.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.forgecode.plugin.idea.settings.ForgeSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 后端通信服务 — 应用级单例
 *
 * 负责与 claude-api-proxy 后端的所有 HTTP 通信：
 * - 健康检查
 * - 获取 / 切换模型
 * - SSE 流式对话
 */
@Service(Service.Level.APP)
class BackendService {

    private val log = logger<BackendService>()
    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** OkHttp 客户端（懒加载，每次读取最新配置） */
    private fun buildClient(): OkHttpClient {
        val settings = ForgeSettings.getInstance()
        return OkHttpClient.Builder()
            .connectTimeout(settings.connectTimeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(settings.readTimeout.toLong(), TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl: String get() = ForgeSettings.getInstance().backendUrl.trimEnd('/')

    // ==================== 数据模型 ====================

    data class ProviderInfo(
        val name: String,
        val displayName: String,
        val region: String,
        val enabled: Boolean,
        val currentModel: String?,
        val models: List<String>,
        val hasApiKey: Boolean
    )

    data class ModelsResponse(
        val activeProvider: String?,
        val activeModel: String?,
        val providers: List<ProviderInfo>
    )

    data class HealthResponse(
        val status: String,
        val version: String?,
        val activeProvider: String?,
        val activeModel: String?
    )

    data class ChatMessage(
        val role: String,
        val content: String
    )

    data class CodeContext(
        val fileName: String? = null,
        val language: String? = null,
        val selectedCode: String? = null,
        val fullFileContent: String? = null
    )

    data class ChatRequest(
        val sessionId: String? = null,
        val messages: List<ChatMessage>,
        val context: CodeContext? = null,
        val stream: Boolean = true
    )

    data class SwitchModelRequest(
        val provider: String,
        val model: String
    )

    data class SwitchModelResponse(
        val success: Boolean,
        val activeProvider: String?,
        val activeModel: String?
    )

    // ==================== 健康检查 ====================

    /**
     * 检查后端是否在线
     * @return true=在线，false=离线
     */
    fun healthCheck(): HealthResponse? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/plugin/health")
                .get()
                .build()
            val response = buildClient().newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                gson.fromJson(body, HealthResponse::class.java)
            } else {
                log.warn("健康检查失败: HTTP ${response.code}")
                null
            }
        } catch (e: IOException) {
            log.warn("健康检查异常: ${e.message}")
            null
        }
    }

    // ==================== 模型管理 ====================

    /**
     * 获取所有可用模型列表及当前激活模型
     */
    fun getModels(): ModelsResponse? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/plugin/models")
                .get()
                .build()
            val response = buildClient().newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                gson.fromJson(body, ModelsResponse::class.java)
            } else {
                log.warn("获取模型列表失败: HTTP ${response.code}")
                null
            }
        } catch (e: IOException) {
            log.warn("获取模型列表异常: ${e.message}")
            null
        }
    }

    /**
     * 切换激活模型
     */
    fun switchModel(provider: String, model: String): SwitchModelResponse? {
        return try {
            val body = gson.toJson(SwitchModelRequest(provider, model))
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$baseUrl/api/plugin/models/active")
                .post(body)
                .build()
            val response = buildClient().newCall(request).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string() ?: return null
                gson.fromJson(respBody, SwitchModelResponse::class.java)
            } else {
                log.warn("切换模型失败: HTTP ${response.code}")
                null
            }
        } catch (e: IOException) {
            log.warn("切换模型异常: ${e.message}")
            null
        }
    }

    // ==================== 流式对话 ====================

    /**
     * 发起流式对话（SSE）
     *
     * @param chatRequest   对话请求
     * @param onToken       收到一个 token 时的回调（在后台线程执行）
     * @param onDone        流式结束时的回调
     * @param onError       出错时的回调
     * @return EventSource 对象，可调用 cancel() 中止流
     */
    fun chat(
        chatRequest: ChatRequest,
        onToken: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Exception) -> Unit
    ): EventSource {
        val body = gson.toJson(chatRequest).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/api/plugin/chat")
            .post(body)
            .header("Accept", "text/event-stream")
            .build()

        val client = buildClient()
        return EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (data == "[DONE]") {
                        onDone()
                        return
                    }
                    try {
                        val token = parseTokenFromChunk(data)
                        if (token != null) onToken(token)
                    } catch (e: Exception) {
                        log.debug("解析 SSE chunk 失败: $data", e)
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    onDone()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    val ex = t?.let { Exception(it) }
                        ?: Exception("SSE 连接失败: HTTP ${response?.code}")
                    log.warn("流式对话失败", ex)
                    onError(ex)
                }
            }
        )
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
            val delta = choices[0].asJsonObject
                .getAsJsonObject("delta") ?: return null
            val content = delta.get("content")
            if (content != null && !content.isJsonNull) content.asString else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun getInstance(): BackendService =
            com.intellij.openapi.components.service()
    }
}
