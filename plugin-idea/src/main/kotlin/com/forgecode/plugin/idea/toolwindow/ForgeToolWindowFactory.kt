package com.forgecode.plugin.idea.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Forge Code 侧边栏 ToolWindow 工厂
 *
 * 在 plugin.xml 中注册，IDEA 启动时自动调用 createToolWindowContent 创建面板。
 */
class ForgeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ForgeChatPanel(project)
        val component = chatPanel.getComponent()
        // 存储 panel 引用，供 EditorUtil.sendMessageToChat 通过 getClientProperty 查找
        component.putClientProperty("forgeChatPanel", chatPanel)
        val content = ContentFactory.getInstance()
            .createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
