package com.codeforge.plugin.idea.toolwindow

import com.intellij.util.messages.Topic

/**
 * 模型切换事件监听器
 *
 * 用于 CodeForgeChatPanel ↔ StatusBarWidget 之间的解耦通信
 */
fun interface ModelChangedListener {
    fun onModelChanged(provider: String, model: String)

    companion object {
        val TOPIC: Topic<ModelChangedListener> =
            Topic.create("CodeForge.ModelChanged", ModelChangedListener::class.java)
    }
}

