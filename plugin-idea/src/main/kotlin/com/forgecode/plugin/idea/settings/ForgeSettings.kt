package com.forgecode.plugin.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Forge Code 插件设置 — 持久化到 forge-code.xml
 *
 * 存储位置: ~/.config/JetBrains/[IDE版本]/options/forge-code.xml
 * 通过 service<ForgeSettings>() 获取单例
 */
@State(
    name = "ForgeCodeSettings",
    storages = [Storage("forge-code.xml")]
)
class ForgeSettings : PersistentStateComponent<ForgeSettings.State> {

    /**
     * 设置数据类
     */
    data class State(
        /** 后端服务地址，默认本地 */
        var backendUrl: String = "http://localhost:8080",
        /** 连接超时（秒） */
        var connectTimeout: Int = 30,
        /** 读取超时（秒） */
        var readTimeout: Int = 120,
        /** UI 主题：auto / dark / light */
        var theme: String = "auto",
        /** 界面语言：zh / en */
        var language: String = "zh",
        /** 默认对话模式：vibe / spec */
        var defaultMode: String = "vibe"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // ==================== 便捷属性 ====================

    var backendUrl: String
        get() = state.backendUrl
        set(value) { state.backendUrl = value }

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

    companion object {
        /** 获取全局单例 */
        fun getInstance(): ForgeSettings = service()
    }
}
