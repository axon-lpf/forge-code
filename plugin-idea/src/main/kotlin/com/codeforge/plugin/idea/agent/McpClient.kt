package com.codeforge.plugin.idea.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * B8：MCP 协议客户端
 *
 * 支持 HTTP/SSE 模式，调用 MCP Server 获取工具列表和执行工具调用。
 */
object McpClient {

    private val log = logger<McpClient>()
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: Map<String, Any>
    )

    data class McpServer(
        val url: String,
        val name: String,
        val tools: List<McpTool> = emptyList()
    )

    /**
     * 列出 MCP Server 提供的工具
     */
    fun listTools(serverUrl: String): List<McpTool> {
        return try {
            val requestBody = gson.toJson(mapOf("jsonrpc" to "2.0", "method" to "tools/list", "id" to 1))
            val request = Request.Builder()
                .url(serverUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseToolsList(body)
            }
        } catch (e: Exception) {
            log.warn("MCP listTools 失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 调用 MCP 工具
     */
    fun callTool(serverUrl: String, toolName: String, arguments: Map<String, Any>): String {
        return try {
            val requestBody = gson.toJson(mapOf(
                "jsonrpc" to "2.0",
                "method" to "tools/call",
                "params" to mapOf("name" to toolName, "arguments" to arguments),
                "id" to 2
            ))
            val request = Request.Builder()
                .url(serverUrl)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "错误: HTTP ${response.code}"
                val body = response.body?.string() ?: return "错误: 空响应"
                parseToolResult(body)
            }
        } catch (e: Exception) {
            log.error("MCP callTool 失败: ${e.message}", e)
            "错误: ${e.message}"
        }
    }

    private fun parseToolsList(json: String): List<McpTool> {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            val toolsArray = obj.getAsJsonArray("tools") ?: return emptyList()
            toolsArray.map { toolObj ->
                val t = toolObj.asJsonObject
                McpTool(
                    name = t.get("name")?.asString ?: "",
                    description = t.get("description")?.asString ?: "",
                    inputSchema = t.get("inputSchema")?.asJsonObject?.let {
                        gson.fromJson(it, Map::class.java) as? Map<String, Any> ?: emptyMap()
                    } ?: emptyMap()
                )
            }.filter { it.name.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseToolResult(json: String): String {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            val result = obj.get("result")
            result?.toString() ?: obj.get("error")?.toString() ?: "未知响应"
        } catch (_: Exception) {
            json
        }
    }
}
