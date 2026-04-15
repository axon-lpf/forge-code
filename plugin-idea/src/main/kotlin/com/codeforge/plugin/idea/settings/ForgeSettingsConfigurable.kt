package com.codeforge.plugin.idea.settings

import com.codeforge.plugin.idea.service.LlmService
import com.codeforge.plugin.llm.ProviderManager
import com.codeforge.plugin.llm.provider.ProviderRegistry
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * 设置页入口 — File → Settings → Tools → CodeForge
 */
class CodeForgeSettingsConfigurable : Configurable {

    private var component: CodeForgeSettingsComponent? = null

    override fun getDisplayName(): String = "CodeForge"

    override fun createComponent(): JComponent {
        component = CodeForgeSettingsComponent()
        return component!!.panel
    }

    override fun isModified(): Boolean {
        val settings = CodeForgeSettings.getInstance()
        val c = component ?: return false
        return c.connectTimeout != settings.connectTimeout ||
                c.readTimeout != settings.readTimeout ||
                c.theme != settings.theme ||
                c.language != settings.language ||
                c.inlineCompletionEnabled != settings.inlineCompletionEnabled ||
                c.isProviderConfigModified()
    }

    override fun apply() {
        val settings = CodeForgeSettings.getInstance()
        val c = component ?: return
        settings.connectTimeout = c.connectTimeout
        settings.readTimeout = c.readTimeout
        settings.theme = c.theme
        settings.language = c.language
        settings.inlineCompletionEnabled = c.inlineCompletionEnabled
        c.applyProviderConfigs()

        // 如果还没有选择激活的 Provider，自动选择第一个已配置的
        if (settings.activeProvider.isBlank()) {
            val configs = settings.getProviderConfigMap()
            val firstConfigured = configs.entries.firstOrNull { !it.value.apiKey.isNullOrBlank() }
            if (firstConfigured != null) {
                settings.activeProvider = firstConfigured.key
                val meta = com.codeforge.plugin.llm.provider.ProviderRegistry.get(firstConfigured.key)
                settings.activeModel = firstConfigured.value.currentModel
                    ?: meta?.defaultModel() ?: ""
            }
        }

        // 重新初始化 LlmService
        LlmService.getInstance().reinitialize()
    }

    override fun reset() {
        val settings = CodeForgeSettings.getInstance()
        val c = component ?: return
        c.connectTimeout = settings.connectTimeout
        c.readTimeout = settings.readTimeout
        c.theme = settings.theme
        c.language = settings.language
        c.loadProviderConfigs()
    }

    override fun disposeUIResources() {
        component = null
    }
}

/**
 * 设置页 UI 组件
 */
class CodeForgeSettingsComponent {

    private val connectTimeoutField = JBTextField("30", 5)
    private val readTimeoutField = JBTextField("120", 5)

    private val themeGroup = ButtonGroup()
    private val themeAuto = JRadioButton("跟随 IDE")
    private val themeDark = JRadioButton("深色")
    private val themeLight = JRadioButton("浅色")

    private val langGroup = ButtonGroup()
    private val langZh = JRadioButton("中文")
    private val langEn = JRadioButton("English")

    // 行内补全开关
    private val inlineCompletionCheckbox = JCheckBox("启用行内代码补全（Ghost Text，Tab 接受）")

    // Provider 配置表格
    private val providerTableModel = ProviderTableModel()
    private val providerTable = JTable(providerTableModel)

    // 编辑中的配置（从 settings 加载，修改后 apply 时保存）
    private val editingConfigs = mutableMapOf<String, ProviderManager.ProviderConfig>()
    private var configModified = false

    val panel: JPanel

    init {
        themeGroup.add(themeAuto); themeGroup.add(themeDark); themeGroup.add(themeLight)
        themeAuto.isSelected = true

        langGroup.add(langZh); langGroup.add(langEn)
        langZh.isSelected = true

        // 初始加载 Provider 配置
        loadProviderConfigs()

        // Provider 表格配置
        providerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        providerTable.rowHeight = 28
        providerTable.columnModel.getColumn(0).preferredWidth = 160  // 提供商
        providerTable.columnModel.getColumn(1).preferredWidth = 200  // API Key
        providerTable.columnModel.getColumn(2).preferredWidth = 80   // 状态

        // 操作按钮
        val editBtn   = JButton("配置")
        val testBtn   = JButton("测试")
        val addCustomBtn = JButton("＋ 自定义")
        val delCustomBtn = JButton("删除")

        editBtn.addActionListener      { editSelectedProvider() }
        testBtn.addActionListener      { testSelectedProvider() }
        addCustomBtn.addActionListener { addCustomProvider() }
        delCustomBtn.addActionListener { deleteSelectedCustomProvider() }

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(editBtn)
            add(testBtn)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 20) })
            add(addCustomBtn)
            add(delCustomBtn)
        }

        // Provider 表格区域
        val tablePanel = JPanel(BorderLayout()).apply {
            add(JScrollPane(providerTable), BorderLayout.CENTER)
            add(btnPanel, BorderLayout.SOUTH)
            preferredSize = Dimension(500, 300)
        }

        // 主题面板
        val themePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(themeAuto); add(themeDark); add(themeLight)
        }
        val langPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(langZh); add(langEn)
        }

        // 初始化行内补全开关状态
        inlineCompletionCheckbox.isSelected = CodeForgeSettings.getInstance().inlineCompletionEnabled

        // 构建表单
        panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("🤖 模型提供商配置"))
            .addComponent(tablePanel)
            .addSeparator()
            .addLabeledComponent(JBLabel("连接超时 (秒):"), connectTimeoutField, true)
            .addLabeledComponent(JBLabel("读取超时 (秒):"), readTimeoutField, true)
            .addSeparator()
            .addLabeledComponent(JBLabel("主题:"), themePanel, true)
            .addLabeledComponent(JBLabel("语言:"), langPanel, true)
            .addSeparator()
            .addComponent(JBLabel("💡 编辑器功能"))
            .addComponent(inlineCompletionCheckbox)
            .addComponent(JBLabel(
                "<html><font color='gray' size='2'>  在编辑器中自动提示 AI 代码补全，按 Tab 接受，Esc 忽略</font></html>"
            ))
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    // ==================== Provider 配置管理 ====================

    fun loadProviderConfigs() {
        editingConfigs.clear()
        editingConfigs.putAll(CodeForgeSettings.getInstance().getProviderConfigMap())
        providerTableModel.fireTableDataChanged()
        configModified = false
    }

    fun isProviderConfigModified(): Boolean = configModified

    fun applyProviderConfigs() {
        CodeForgeSettings.getInstance().setProviderConfigMap(editingConfigs)
        configModified = false
    }

    private fun editSelectedProvider() {
        val row = providerTable.selectedRow
        if (row < 0) {
            Messages.showInfoMessage("请先选择一个提供商", "提示")
            return
        }

        val allMeta = ProviderRegistry.getAll().entries.toList()
        val (name, meta) = allMeta[row]

        val existingConfig = editingConfigs[name]
        val dialog = ProviderConfigDialog(name, meta, existingConfig)

        if (dialog.showAndGet()) {
            val newConfig = dialog.getConfig()
            editingConfigs[name] = newConfig
            configModified = true
            providerTableModel.fireTableRowsUpdated(row, row)
        }
    }

    /** 添加自定义 Provider */
    private fun addCustomProvider() {
        val dialog = CustomProviderDialog(null)
        if (dialog.showAndGet()) {
            val r = dialog.getData()
            ProviderRegistry.registerCustom(r.id, r.displayName, r.baseUrl, 
                r.models.split(",").map { it.trim() }.filter { it.isNotBlank() })
            val config = ProviderManager.ProviderConfig().apply {
                enabled = true
                apiKey = r.apiKey.ifBlank { null }
                baseUrl = r.baseUrl
                currentModel = r.models.split(",").firstOrNull()?.trim() ?: "default"
            }
            editingConfigs[ProviderRegistry.CUSTOM_PREFIX + r.id] = config
            // API Key 写入 PasswordSafe
            if (r.apiKey.isNotBlank()) {
                CodeForgeSettings.getInstance().saveApiKey(ProviderRegistry.CUSTOM_PREFIX + r.id, r.apiKey)
            }
            configModified = true
            providerTableModel.fireTableDataChanged()
        }
    }

    /** 删除选中的自定义 Provider */
    private fun deleteSelectedCustomProvider() {
        val row = providerTable.selectedRow
        if (row < 0) { Messages.showInfoMessage("请先选择要删除的提供商", "提示"); return }
        val allMeta = ProviderRegistry.getAll().entries.toList()
        val (name, meta) = allMeta[row]
        if (!ProviderRegistry.isCustom(name)) {
            Messages.showWarningDialog("内置提供商不能删除，可以留空 API Key 来停用", "无法删除")
            return
        }
        val ok = Messages.showYesNoDialog("确认删除「${meta.displayName()}」？", "删除确认", Messages.getQuestionIcon())
        if (ok == Messages.YES) {
            ProviderRegistry.removeCustom(name)
            editingConfigs.remove(name)
            configModified = true
            providerTableModel.fireTableDataChanged()
        }
    }

    private fun testSelectedProvider() {
        val row = providerTable.selectedRow
        if (row < 0) {
            Messages.showInfoMessage("请先选择一个提供商", "提示")
            return
        }

        val allMeta = ProviderRegistry.getAll().entries.toList()
        val (name, _) = allMeta[row]
        val config = editingConfigs[name]

        if (config == null || config.apiKey.isNullOrBlank()) {
            Messages.showWarningDialog("请先配置 API Key", "未配置")
            return
        }

        // 异步测试
        Thread {
            val result = LlmService.getInstance().testProvider(name, config.apiKey, config.baseUrl, config.currentModel)
            SwingUtilities.invokeLater {
                if (result.success) {
                    Messages.showInfoMessage(
                        "✅ 连接成功！\n\n模型: ${result.model}\n响应时间: ${result.responseTime}ms\n回复: ${result.response}",
                        "测试成功 — ${ProviderRegistry.get(name)?.displayName() ?: name}"
                    )
                } else {
                    Messages.showErrorDialog(
                        "❌ 连接失败\n\n错误: ${result.error}",
                        "测试失败 — ${ProviderRegistry.get(name)?.displayName() ?: name}"
                    )
                }
            }
        }.start()
    }

    // ==================== 表格模型 ====================

    inner class ProviderTableModel : AbstractTableModel() {
        private val columns = arrayOf("提供商", "API Key", "状态")

        override fun getRowCount(): Int = ProviderRegistry.getAll().size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(col: Int): String = columns[col]

        override fun getValueAt(row: Int, col: Int): Any {
            val allMeta = ProviderRegistry.getAll().entries.toList()
            val (name, meta) = allMeta[row]
            val config = editingConfigs[name]

            return when (col) {
                0 -> {
                    val flag = when (meta.region()) {
                        "cn" -> "🇨🇳"
                        "custom" -> "🔧"
                        else -> "🌍"
                    }
                    "$flag ${meta.displayName()}"
                }
                1 -> {
                    val key = config?.apiKey
                    if (key.isNullOrBlank()) "(未配置)"
                    else maskApiKey(key)
                }
                2 -> {
                    if (config?.apiKey.isNullOrBlank()) "⚠️ 未配置"
                    else "✅ 已配置"
                }
                else -> ""
            }
        }

        private fun maskApiKey(key: String): String {
            if (key.length <= 8) return "****"
            return key.substring(0, 3) + "****" + key.substring(key.length - 4)
        }
    }

    // ==================== 属性 ====================

    var connectTimeout: Int
        get() = connectTimeoutField.text.toIntOrNull() ?: 30
        set(value) { connectTimeoutField.text = value.toString() }

    var readTimeout: Int
        get() = readTimeoutField.text.toIntOrNull() ?: 120
        set(value) { readTimeoutField.text = value.toString() }

    var theme: String
        get() = when {
            themeDark.isSelected -> "dark"
            themeLight.isSelected -> "light"
            else -> "auto"
        }
        set(value) {
            when (value) {
                "dark" -> themeDark.isSelected = true
                "light" -> themeLight.isSelected = true
                else -> themeAuto.isSelected = true
            }
        }

    var language: String
        get() = if (langEn.isSelected) "en" else "zh"
        set(value) {
            if (value == "en") langEn.isSelected = true else langZh.isSelected = true
        }

    var inlineCompletionEnabled: Boolean
        get() = inlineCompletionCheckbox.isSelected
        set(value) { inlineCompletionCheckbox.isSelected = value }
}


class CustomProviderDialog(existing: CustomProviderData?) : DialogWrapper(true) {

    data class CustomProviderData(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val apiKey: String,
        val models: String      // 逗号分隔
    )

    private val idField = JBTextField(existing?.id ?: "", 20).apply {
        emptyText.text = "唯一标识，如 my-ollama（字母数字-_）"
    }
    private val nameField = JBTextField(existing?.displayName ?: "", 20).apply {
        emptyText.text = "显示名称，如 My Ollama"
    }
    private val baseUrlField = JBTextField(existing?.baseUrl ?: "", 30).apply {
        emptyText.text = "如 http://localhost:11434/v1"
    }
    private val apiKeyField = JBTextField(existing?.apiKey ?: "", 30).apply {
        emptyText.text = "无需 API Key 时留空"
    }
    private val modelsField = JBTextField(existing?.models ?: "", 30).apply {
        emptyText.text = "如 llama3,mistral（逗号分隔）"
    }

    init {
        title = "添加自定义 API 提供商"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(JBLabel("ID:"), idField, true)
        .addLabeledComponent(JBLabel("名称:"), nameField, true)
        .addLabeledComponent(JBLabel("Base URL:"), baseUrlField, true)
        .addLabeledComponent(JBLabel("API Key:"), apiKeyField, true)
        .addLabeledComponent(JBLabel("模型列表:"), modelsField, true)
        .addComponent(JBLabel(
            "<html><font color='gray' size='2'>" +
            "支持 Ollama（http://localhost:11434/v1）、LM Studio（http://localhost:1234/v1）" +
            "等任意 OpenAI 兼容接口。无需 API Key 时留空。" +
            "</font></html>"
        ))
        .panel

    fun getData() = CustomProviderData(
        id = idField.text.trim().replace(" ", "_"),
        displayName = nameField.text.trim(),
        baseUrl = baseUrlField.text.trim(),
        apiKey = apiKeyField.text.trim(),
        models = modelsField.text.trim()
    )
}

// ==================== Provider 配置编辑对话框 ====================

/**
 * 配置某个提供商的 API Key、Base URL、模型等
 */
class ProviderConfigDialog(
    private val providerName: String,
    private val meta: ProviderRegistry.ProviderMeta,
    existingConfig: ProviderManager.ProviderConfig?
) : DialogWrapper(true) {

    private val apiKeyField = JBTextField(existingConfig?.apiKey ?: "", 30)
    private val baseUrlField = JBTextField(existingConfig?.baseUrl ?: "", 30)
    private val websiteLabel = JBLabel("<html><a href='${meta.website()}'>${meta.website()}</a></html>")

    /**
     * 是否需要手动输入模型 ID（如豆包的接入点 ep-xxx）
     * 判断规则：模型列表只有一个占位项，或模型名含 "your-" / "ep-"
     */
    private val needsCustomModelInput: Boolean = providerName == "doubao" ||
            meta.models().any { it.contains("your-") || it.startsWith("ep-") }

    // 豆包等需要手动输入的提供商 → 用 TextField
    private val modelTextField: JBTextField? = if (needsCustomModelInput) {
        JBTextField(existingConfig?.currentModel ?: "", 30).apply {
            emptyText.text = "请输入接入点 ID（如 ep-xxx）"
        }
    } else null

    // 其他提供商 → 用可编辑的下拉框（既能选也能自定义输入）
    private val modelCombo: JComboBox<String>? = if (!needsCustomModelInput) {
        JComboBox(meta.models().toTypedArray()).apply {
            isEditable = true  // 允许自由输入
        }
    } else null

    init {
        title = "配置 ${meta.displayName()}"

        // 设置当前选中的模型
        val currentModel = existingConfig?.currentModel ?: meta.defaultModel()
        if (modelCombo != null) {
            modelCombo.selectedItem = currentModel
        }

        // Base URL placeholder
        baseUrlField.emptyText.text = meta.defaultBaseUrl() + "（留空使用默认）"

        init()
    }

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField, true)
            .addLabeledComponent(JBLabel("Base URL:"), baseUrlField, true)

        if (needsCustomModelInput) {
            // 豆包：模型 ID 输入框 + 额外提示
            builder.addLabeledComponent(JBLabel("模型 ID:"), modelTextField!!, true)
            builder.addComponent(JBLabel(
                "<html><font color='#dcdcaa' size='2'>⚠ 豆包需要在" +
                "<a href='https://console.volcengine.com/ark'>火山引擎方舟平台</a>" +
                "创建接入点，将接入点 ID（ep-xxx）填入上方</font></html>"
            ))
        } else {
            // 其他：可编辑下拉框
            builder.addLabeledComponent(JBLabel("模型:"), modelCombo!!, true)
        }

        builder.addComponent(JBLabel(" "))
            .addLabeledComponent(JBLabel("获取 API Key →"), websiteLabel, true)
            .addComponent(JBLabel("<html><font color='gray' size='2'>${meta.description()}</font></html>"))

        return builder.panel
    }

    fun getConfig(): ProviderManager.ProviderConfig {
        val config = ProviderManager.ProviderConfig()
        config.enabled = true
        config.apiKey = apiKeyField.text.trim()
        config.baseUrl = baseUrlField.text.trim().ifBlank { null }
        config.currentModel = if (needsCustomModelInput) {
            modelTextField?.text?.trim()?.ifBlank { null } ?: meta.defaultModel()
        } else {
            modelCombo?.selectedItem as? String ?: meta.defaultModel()
        }
        config.connectTimeout = CodeForgeSettings.getInstance().connectTimeout
        config.readTimeout = CodeForgeSettings.getInstance().readTimeout
        return config
    }
}

