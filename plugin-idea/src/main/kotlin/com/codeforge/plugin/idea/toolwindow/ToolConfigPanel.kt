package com.codeforge.plugin.idea.toolwindow

import com.codeforge.plugin.idea.settings.CodeForgeSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * A5：工具配置面板
 * 列出所有 Agent 工具，支持单独设置 AUTO / 开启 / 关闭
 */
class ToolConfigPanel(private val project: Project) : DialogWrapper(true) {

    data class ToolDef(
        val id: String,
        val name: String,
        val description: String
    )

    private val allTools = listOf(
        ToolDef("read_file", "仓库读取", "读取项目文件内容"),
        ToolDef("write_file", "仓库写入", "创建或修改项目文件"),
        ToolDef("search_code", "代码检索", "搜索项目中的代码/类/方法"),
        ToolDef("apply_code", "代码Apply", "将 AI 生成的代码应用到编辑器"),
        ToolDef("run_terminal", "执行命令", "在终端执行 Shell 命令"),
        ToolDef("list_files", "文件列表", "列出目录下的文件"),
        ToolDef("glob", "文件匹配", "按模式查找项目文件"),
        ToolDef("grep", "文本搜索", "在文件中搜索文本内容"),
        ToolDef("plan_mode", "Plan Mode", "先规划后执行的规划模式"),
        ToolDef("clarify", "需求澄清", "向用户追问澄清需求细节"),
        ToolDef("mcp", "MCP 工具", "调用 MCP Server 提供的工具")
    )

    private val tableModel = ToolTableModel()
    private val table = JTable(tableModel)
    private val modeCombo = JComboBox(arrayOf("AUTO", "ENABLED", "DISABLED"))

    init {
        title = "工具配置 — CodeForge"
        table.columnModel.getColumn(2).cellEditor = DefaultCellEditor(modeCombo)
        table.columnModel.getColumn(2).cellRenderer = DefaultTableCellRenderer()
        init()
    }

    override fun createCenterPanel(): JComponent {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowHeight = 30
        table.columnModel.getColumn(0).preferredWidth = 120
        table.columnModel.getColumn(1).preferredWidth = 200
        table.columnModel.getColumn(2).preferredWidth = 120

        val scrollPane = JScrollPane(table)
        scrollPane.preferredSize = Dimension(500, 340)

        val headerLabel = JBLabel(
            "<html><b>Agent 工具配置</b><br>" +
            "<font color='gray' size='2'>AUTO = 按场景自动判断 | 开启 = 始终可用 | 关闭 = 始终禁用</font></html>"
        )

        return FormBuilder.createFormBuilder()
            .addComponent(headerLabel, 1)
            .addComponent(scrollPane, 1)
            .addComponent(JBLabel(" "))
            .panel
    }

    override fun doOKAction() {
        // 从表格中读取所有工具模式并保存
        for (row in allTools.indices) {
            val tool = allTools[row]
            val selected = tableModel.getValueAt(row, 2) as? String ?: "AUTO"
            val mode = try {
                CodeForgeSettings.ToolMode.valueOf(selected)
            } catch (_: Exception) {
                CodeForgeSettings.ToolMode.AUTO
            }
            CodeForgeSettings.getInstance().setToolMode(tool.id, mode)
        }
        super.doOKAction()
    }

    inner class ToolTableModel : DefaultTableModel(arrayOf<Any>("工具", "说明", "模式"), 0) {

        init {
            for (tool in allTools) {
                addRow(arrayOf(tool.name, tool.description, CodeForgeSettings.getInstance().getToolMode(tool.id).name))
            }
        }

        override fun isCellEditable(row: Int, col: Int): Boolean = col == 2

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 2 && value != null) {
                val mode = try {
                    CodeForgeSettings.ToolMode.valueOf(value.toString())
                } catch (_: Exception) {
                    CodeForgeSettings.ToolMode.AUTO
                }
                CodeForgeSettings.getInstance().setToolMode(allTools[row].id, mode)
                super.setValueAt(mode.name, row, col)
            }
        }
    }

    companion object {
        fun show(project: Project) {
            val panel = ToolConfigPanel(project)
            panel.show()
        }
    }
}
