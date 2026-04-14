package com.forgecode.plugin.idea.settings

import com.forgecode.plugin.llm.ProviderManager
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
 * Forge Code 插件设置 — 持久化到 forge-code.xml
 *
 * 存储位置: ~/.config/JetBrains/[IDE版本]/options/forge-code.xml
 * 通过 service<ForgeSettings>() 获取单例
 *
 * v1.0 变化：新增 Provider 配置存储（API Key、模型选择等），
 * 去掉 backendUrl（不再依赖外部后端）。
 */
@State(
    name = "ForgeCodeSettings",
    storages = [Storage("forge-code.xml")]
)
class ForgeSettings : PersistentStateComponent<ForgeSettings.State> {

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
        var inlineCompletionDelayMs: Int = 500
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
            val attrs = CredentialAttributes("ForgeCode.$providerName")
            PasswordSafe.instance.getPassword(attrs)
        } catch (e: Exception) {
            null
        }
    }

    /** 存储 API Key 到系统钥匙串 */
    fun saveApiKey(providerName: String, apiKey: String) {
        try {
            val attrs = CredentialAttributes("ForgeCode.$providerName")
            PasswordSafe.instance.set(attrs, Credentials("forgecode", apiKey))
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
        fun getInstance(): ForgeSettings = service()
    }
}
