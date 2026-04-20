package com.codeforge.plugin.idea.settings

import com.codeforge.plugin.llm.ProviderManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * CodeForge 插件设置 — 持久化到 codeforge.xml
 *
 * 存储位置: ~/.config/JetBrains/[IDE版本]/options/codeforge.xml
 * 通过 service<CodeForgeSettings>() 获取单例
 *
 * v1.0 变化：新增 Provider 配置存储（API Key、模型选择等），
 * 去掉 backendUrl（不再依赖外部后端）。
 */
@State(
    name = "CodeCodeForgeSettings",
    storages = [Storage("codeforge.xml")]
)
class CodeForgeSettings : PersistentStateComponent<CodeForgeSettings.State> {

    private val gson = Gson()

    /**
     * 设置数据类
     */
    data class State(
        // ===== 模型配置 =====
        /** 当前激活的提供商名称 (如: deepseek) */
        var activeProvider: String = "",
        /** 当前激活的模型名称 (如: deepseek-chat) */
        var activeModel: String = "",
        /** 各提供商配置 (JSON 序列化存储) */
        var providerConfigs: String = "{}",

        // ===== 通用设置 =====
        /** 连接超时（秒） */
        var connectTimeout: Int = 30,
        /** 读取超时（秒） */
        var readTimeout: Int = 120,

        // ===== 界面设置 =====
        /** UI 主题：auto / dark / light */
        var theme: String = "auto",
        /** 界面语言：zh / en */
        var language: String = "zh",
        /** 默认对话模式：vibe / spec */
        var defaultMode: String = "vibe",
        /** 是否启用行内代码补全（Ghost Text） */
        var inlineCompletionEnabled: Boolean = true,
        /** 行内补全触发延迟（毫秒） */
        var inlineCompletionDelayMs: Int = 500,
        /** 工具配置：工具名 → AUTO/ENABLED/DISABLED */
        var toolConfig: String = "{}",
        /** 错误自动修复循环 */
        var autoFixEnabled: Boolean = true,
        /** 自动修复最大重试次数 */
        var autoFixMaxRetries: Int = 3,
        /** B2：Memory 数据 JSON */
        var memoryData: String = "[]",
        /** B6：自定义 Prompt 模板 JSON */
        var promptTemplates: String = "[]",
        /** B8：MCP Server 配置列表 JSON */
        var mcpServers: String = "[]"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // ==================== 便捷属性 ====================

    var activeProvider: String
        get() = state.activeProvider
        set(value) { state.activeProvider = value }

    var activeModel: String
        get() = state.activeModel
        set(value) { state.activeModel = value }

    var connectTimeout: Int
        get() = state.connectTimeout
        set(value) { state.connectTimeout = value }

    var readTimeout: Int
        get() = state.readTimeout
        set(value) { state.readTimeout = value }

    var theme: String
        get() = state.theme
        set(value) { state.theme = value }

    var language: String
        get() = state.language
        set(value) { state.language = value }

    var defaultMode: String
        get() = state.defaultMode
        set(value) { state.defaultMode = value }

    var inlineCompletionEnabled: Boolean
        get() = state.inlineCompletionEnabled
        set(value) { state.inlineCompletionEnabled = value }

    var inlineCompletionDelayMs: Int
        get() = state.inlineCompletionDelayMs
        set(value) { state.inlineCompletionDelayMs = value }

    /** 工具配置：工具名 → AUTO/ENABLED/DISABLED */
    var toolConfig: String
        get() = state.toolConfig
        set(value) { state.toolConfig = value }

    var autoFixEnabled: Boolean
        get() = state.autoFixEnabled
        set(value) { state.autoFixEnabled = value }

    var autoFixMaxRetries: Int
        get() = state.autoFixMaxRetries
        set(value) { state.autoFixMaxRetries = value }

    var memoryData: String
        get() = state.memoryData
        set(value) { state.memoryData = value }

    var promptTemplates: String
        get() = state.promptTemplates
        set(value) { state.promptTemplates = value }

    /** B8：MCP Server 列表 */
    var mcpServers: String
        get() = state.mcpServers
        set(value) { state.mcpServers = value }

    // ==================== 工具配置 ====================

    enum class ToolMode { AUTO, ENABLED, DISABLED }

    fun getToolMode(toolName: String): ToolMode {
        return try {
            val map = gson.fromJson(state.toolConfig, Map::class.java) as? Map<String, String> ?: emptyMap()
            (map[toolName] ?: "AUTO").let { ToolMode.valueOf(it) }
        } catch (_: Exception) { ToolMode.AUTO }
    }

    fun setToolMode(toolName: String, mode: ToolMode) {
        val map = try {
            gson.fromJson(state.toolConfig, Map::class.java) as? MutableMap<String, String> ?: mutableMapOf()
        } catch (_: Exception) { mutableMapOf() }
        map[toolName] = mode.name
        state.toolConfig = gson.toJson(map)
    }

    // ==================== B8：MCP Server 配置 ====================

    /** MCP Server 配置项 */
    data class McpServerConfig(
        val url: String,
        val name: String,
        val enabled: Boolean = true
    )

    /** 获取所有 MCP Server 配置 */
    fun getMcpServers(): List<McpServerConfig> {
        return try {
            val type = object : TypeToken<List<McpServerConfig>>() {}.type
            gson.fromJson(state.mcpServers, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存 MCP Server 配置列表 */
    fun setMcpServers(servers: List<McpServerConfig>) {
        state.mcpServers = gson.toJson(servers)
    }

    /** 添加一个 MCP Server */
    fun addMcpServer(url: String, name: String) {
        val servers = getMcpServers().toMutableList()
        servers.removeAll { it.url == url || it.name == name }
        servers.add(McpServerConfig(url = url, name = name, enabled = true))
        setMcpServers(servers)
    }

    /** 删除一个 MCP Server */
    fun removeMcpServer(url: String) {
        val servers = getMcpServers().filterNot { it.url == url }
        setMcpServers(servers)
    }

    /** 切换 MCP Server 启用状态 */
    fun setMcpServerEnabled(url: String, enabled: Boolean) {
        val servers = getMcpServers().map {
            if (it.url == url) it.copy(enabled = enabled) else it
        }
        setMcpServers(servers)
    }

    // ==================== Provider 配置读写 ====================

    /**
     * 将 providerConfigs JSON 转为 Map<String, ProviderManager.ProviderConfig>
     * API Key 从 PasswordSafe 补充（XML 中只存脱敏值）
     */
    fun getProviderConfigMap(): Map<String, ProviderManager.ProviderConfig> {
        return try {
            val type = object : TypeToken<Map<String, ProviderManager.ProviderConfig>>() {}.type
            val map: Map<String, ProviderManager.ProviderConfig> =
                gson.fromJson(state.providerConfigs, type) ?: emptyMap()
            // 从 PasswordSafe 补充真实 API Key
            map.mapValues { (name, config) ->
                val realKey = loadApiKey(name)
                if (!realKey.isNullOrBlank()) config.apply { apiKey = realKey } else config
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 保存整个 Provider 配置 Map
     * API Key 存入 PasswordSafe，XML 中只存脱敏显示值
     */
    fun setProviderConfigMap(configs: Map<String, ProviderManager.ProviderConfig>) {
        val safeConfigs = configs.mapValues { (name, config) ->
            val key = config.apiKey
            if (!key.isNullOrBlank()) {
                saveApiKey(name, key)                       // 写入系统钥匙串
                config.apply { apiKey = maskApiKey(key) }   // XML 只存脱敏值
            } else config
        }
        state.providerConfigs = gson.toJson(safeConfigs)
    }

    // ==================== PasswordSafe API Key 操作 ====================

    /** 从系统钥匙串读取 API Key */
    fun loadApiKey(providerName: String): String? {
        return try {
            val attrs = CredentialAttributes("CodeForge.$providerName")
            PasswordSafe.instance.getPassword(attrs)
        } catch (e: Exception) {
            null
        }
    }

    /** 存储 API Key 到系统钥匙串 */
    fun saveApiKey(providerName: String, apiKey: String) {
        try {
            val attrs = CredentialAttributes("CodeForge.$providerName")
            PasswordSafe.instance.set(attrs, Credentials("CodeForge", apiKey))
        } catch (_: Exception) {}
    }

    /** 脱敏显示：sk-abcdefg1234 → sk-****1234 */
    private fun maskApiKey(key: String): String {
        if (key.length <= 8) return "****"
        val prefix = key.take(if (key.startsWith("sk-")) 3 else 2)
        return "$prefix****${key.takeLast(4)}"
    }

    /**
     * 保存单个 Provider 配置
     */
    fun setProviderConfig(name: String, config: ProviderManager.ProviderConfig) {
        val map = getProviderConfigMap().toMutableMap()
        map[name] = config
        setProviderConfigMap(map)
    }

    /**
     * 获取单个 Provider 配置
     */
    fun getProviderConfig(name: String): ProviderManager.ProviderConfig? {
        return getProviderConfigMap()[name]
    }

    /**
     * 删除单个 Provider 配置
     */
    fun removeProviderConfig(name: String) {
        val map = getProviderConfigMap().toMutableMap()
        map.remove(name)
        setProviderConfigMap(map)
    }

    companion object {
        /** 获取全局单例 */
        fun getInstance(): CodeForgeSettings = service()
    }
}

