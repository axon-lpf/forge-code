package com.codeforge.plugin.idea.toolwindow

import com.codeforge.plugin.idea.settings.CodeForgeSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * A5：工具配置面板
 *
 * 弹窗显示所有 Agent 工具的启用状态，支持逐项设置 Auto / 启用 / 关闭。
 * 通过 /tools 指令或 Kotlin 侧直接创建并调用 show() 打开。
 */
class ToolConfigPanel(project: Project) : DialogWrapper(project, true) {

    /** 工具描述（展示名称） */
    private val toolDescriptions = linkedMapOf(
        "read_file"    to "📖  仓库文件读取",
        "write_file"   to "✏️  代码 Apply（写文件）",
        "list_files"   to "📁  Glob 文件搜索",
        "search_code"  to "🔍  Grep 内容搜索",
        "run_terminal" to "⚡  执行终端命令（CMD）"
    )

    private val MODE_LABELS = arrayOf("Auto（AI 自主决策）", "启用", "关闭")
    private val MODE_VALUES = arrayOf(
        CodeForgeSettings.ToolMode.AUTO,
        CodeForgeSettings.ToolMode.ENABLED,
        CodeForgeSettings.ToolMode.DISABLED
    )

    /** 每个工具对应的下拉框 */
    private val selectors = linkedMapOf<String, JComboBox<String>>()

    init {
        title = "CodeForge — 工具配置"
        isResizable = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val settings = CodeForgeSettings.getInstance()
        val panel = JPanel(GridBagLayout())
        panel.preferredSize = Dimension(420, -1)

        // 说明标签
        val hint = JLabel(
            "<html><div style='color:#888;font-size:11px'>" +
            "Auto：AI 根据任务自主决定是否调用该工具<br>" +
            "启用：强制允许调用 &nbsp;·&nbsp; 关闭：禁止调用</div></html>"
        )
        val hintGbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0; gridwidth = 2
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, 8, 12, 8)
        }
        panel.add(hint, hintGbc)

        // 每个工具一行
        toolDescriptions.entries.forEachIndexed { i, (tool, desc) ->
            val labelGbc = GridBagConstraints().apply {
                gridx = 0; gridy = i + 1
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                insets = Insets(4, 8, 4, 16)
                weightx = 1.0
            }
            panel.add(JLabel(desc), labelGbc)

            val combo = JComboBox(MODE_LABELS)
            val currentMode = settings.getToolMode(tool)
            combo.selectedIndex = MODE_VALUES.indexOfFirst { it == currentMode }.coerceAtLeast(0)
            selectors[tool] = combo

            val comboGbc = GridBagConstraints().apply {
                gridx = 1; gridy = i + 1
                fill = GridBagConstraints.NONE
                anchor = GridBagConstraints.EAST
                insets = Insets(4, 0, 4, 8)
            }
            panel.add(combo, comboGbc)
        }

        return panel
    }

    override fun doOKAction() {
        val settings = CodeForgeSettings.getInstance()
        selectors.forEach { (tool, combo) ->
            settings.setToolMode(tool, MODE_VALUES[combo.selectedIndex])
        }
        super.doOKAction()
    }
}
