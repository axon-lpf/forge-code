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
        scrollPane.preferredSize = Dimension(500, 300)

        val headerLabel = JBLabel(
            "<html><b>Agent 工具配置</b><br>" +
            "<font color='gray' size='2'>AUTO = 按场景自动判断 | 开启 = 始终可用 | 关闭 = 始终禁用</font></html>"
        )

        // B8：MCP Server 配置按钮
        val mcpButton = JButton("MCP Server 配置...")
        mcpButton.addActionListener {
            showMcpConfigDialog()
        }

        return FormBuilder.createFormBuilder()
            .addComponent(headerLabel, 1)
            .addComponent(scrollPane, 1)
            .addComponent(JBLabel(" "))
            .addComponent(mcpButton, 1)
            .panel
    }

    /**
     * B8：显示 MCP Server 配置弹窗
     */
    private fun showMcpConfigDialog() {
        val dialog = McpConfigDialog(project)
        dialog.show()
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

/**
 * B8：MCP Server 配置弹窗
 * 允许用户添加/删除/启用/禁用 MCP Server，并显示已连接的工具列表
 */
class McpConfigDialog(private val project: Project) : DialogWrapper(true) {

    private val settings = CodeForgeSettings.getInstance()
    private val servers = settings.getMcpServers().toMutableList()

    private val tableModel = McpServerTableModel()
    private val table = JTable(tableModel)

    init {
        title = "MCP Server 配置 — CodeForge"
        init()
    }

    override fun createCenterPanel(): JComponent {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowHeight = 28
        table.columnModel.getColumn(0).preferredWidth = 40  // 启用
        table.columnModel.getColumn(1).preferredWidth = 150 // 名称
        table.columnModel.getColumn(2).preferredWidth = 250 // URL
        table.columnModel.getColumn(3).preferredWidth = 80  // 工具数

        val scrollPane = JScrollPane(table)
        scrollPane.preferredSize = Dimension(550, 280)

        val addBtn = JButton("添加 Server")
        addBtn.addActionListener { addServer() }

        val removeBtn = JButton("删除选中")
        removeBtn.addActionListener {
            val row = table.selectedRow
            if (row >= 0 && row < servers.size) {
                servers.removeAt(row)
                tableModel.reload()
            }
        }

        val testBtn = JButton("测试连接")
        testBtn.addActionListener { testConnection() }

        val btnPanel = JPanel()
        btnPanel.layout = BoxLayout(btnPanel, BoxLayout.X_AXIS)
        btnPanel.add(addBtn)
        btnPanel.add(Box.createHorizontalStrut(8))
        btnPanel.add(removeBtn)
        btnPanel.add(Box.createHorizontalStrut(8))
        btnPanel.add(testBtn)

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("<html><b>MCP Server 管理</b></html>"), 1)
            .addComponent(scrollPane, 1)
            .addComponent(btnPanel, 1)
            .panel
    }

    private fun addServer() {
        val parent = rootPane
        val name = JOptionPane.showInputDialog(parent, "Server 名称:", "MCP Server")
            ?: return
        val url = JOptionPane.showInputDialog(parent, "Server URL (如 http://localhost:8080):", "http://localhost:")
            ?: return
        if (url.isBlank()) return
        servers.add(CodeForgeSettings.McpServerConfig(url = url, name = name, enabled = true))
        tableModel.reload()
    }

    private fun testConnection() {
        val row = table.selectedRow
        if (row < 0 || row >= servers.size) {
            JOptionPane.showMessageDialog(rootPane, "请先选中一个 Server")
            return
        }
        val server = servers[row]
        val tools = com.codeforge.plugin.idea.agent.McpClient.listTools(server.url)
        if (tools.isEmpty()) {
            JOptionPane.showMessageDialog(rootPane, "连接失败或无可用工具", "测试结果", JOptionPane.WARNING_MESSAGE)
        } else {
            JOptionPane.showMessageDialog(rootPane, "连接成功！发现 ${tools.size} 个工具:\n${tools.joinToString { it.name }}", "测试结果", JOptionPane.INFORMATION_MESSAGE)
        }
    }

    override fun doOKAction() {
        settings.setMcpServers(servers)
        super.doOKAction()
    }

    inner class McpServerTableModel : DefaultTableModel(arrayOf<Any>("启用", "名称", "URL", "工具数"), 0) {

        init { reload() }

        fun reload() {
            setRowCount(0)
            for (server in servers) {
                val toolCount = if (server.enabled) {
                    com.codeforge.plugin.idea.agent.McpClient.listTools(server.url).size
                } else 0
                addRow(arrayOf(server.enabled, server.name, server.url, if (toolCount > 0) toolCount else "-"))
            }
        }

        override fun isCellEditable(row: Int, col: Int): Boolean = col == 0

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 0 && row < servers.size) {
                servers[row] = servers[row].copy(enabled = (value as? Boolean) ?: true)
            }
        }
    }
}
