package com.forgecode.plugin.idea.settings

import com.forgecode.plugin.idea.service.BackendService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.FlowLayout
import javax.swing.*

/**
 * 设置页入口 — File → Settings → Tools → Forge Code
 */
class ForgeSettingsConfigurable : Configurable {

    private var component: ForgeSettingsComponent? = null

    override fun getDisplayName(): String = "Forge Code"

    override fun createComponent(): JComponent {
        component = ForgeSettingsComponent()
        return component!!.panel
    }

    override fun isModified(): Boolean {
        val settings = ForgeSettings.getInstance()
        val c = component ?: return false
        return c.backendUrl != settings.backendUrl ||
                c.connectTimeout != settings.connectTimeout ||
                c.readTimeout != settings.readTimeout ||
                c.theme != settings.theme ||
                c.language != settings.language
    }

    override fun apply() {
        val settings = ForgeSettings.getInstance()
        val c = component ?: return
        settings.backendUrl = c.backendUrl
        settings.connectTimeout = c.connectTimeout
        settings.readTimeout = c.readTimeout
        settings.theme = c.theme
        settings.language = c.language
    }

    override fun reset() {
        val settings = ForgeSettings.getInstance()
        val c = component ?: return
        c.backendUrl = settings.backendUrl
        c.connectTimeout = settings.connectTimeout
        c.readTimeout = settings.readTimeout
        c.theme = settings.theme
        c.language = settings.language
    }

    override fun disposeUIResources() {
        component = null
    }
}

/**
 * 设置页 UI 组件
 */
class ForgeSettingsComponent {

    private val backendUrlField = JBTextField()
    private val connectTimeoutField = JBTextField("30", 5)
    private val readTimeoutField = JBTextField("120", 5)

    private val themeGroup = ButtonGroup()
    private val themeAuto = JRadioButton("跟随 IDE")
    private val themeDark = JRadioButton("深色")
    private val themeLight = JRadioButton("浅色")

    private val langGroup = ButtonGroup()
    private val langZh = JRadioButton("中文")
    private val langEn = JRadioButton("English")

    private val testConnectBtn = JButton("测试连接")
    private val testResultLabel = JBLabel("")

    val panel: JPanel

    init {
        // 主题单选组
        themeGroup.add(themeAuto); themeGroup.add(themeDark); themeGroup.add(themeLight)
        themeAuto.isSelected = true

        // 语言单选组
        langGroup.add(langZh); langGroup.add(langEn)
        langZh.isSelected = true

        // 测试连接按钮
        testConnectBtn.addActionListener {
            testConnection()
        }

        // 构建表单
        val themePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(themeAuto); add(themeDark); add(themeLight)
        }
        val langPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(langZh); add(langEn)
        }
        val testPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(testConnectBtn); add(testResultLabel)
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("后端服务地址:"), backendUrlField, true)
            .addLabeledComponent(JBLabel("连接超时 (秒):"), connectTimeoutField, true)
            .addLabeledComponent(JBLabel("读取超时 (秒):"), readTimeoutField, true)
            .addComponent(testPanel)
            .addSeparator()
            .addLabeledComponent(JBLabel("主题:"), themePanel, true)
            .addLabeledComponent(JBLabel("语言:"), langPanel, true)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun testConnection() {
        testResultLabel.text = "⏳ 连接中..."
        testConnectBtn.isEnabled = false
        Thread {
            val service = BackendService.getInstance()
            val health = service.healthCheck()
            SwingUtilities.invokeLater {
                testConnectBtn.isEnabled = true
                if (health != null) {
                    testResultLabel.text = "✅ 已连接，当前模型: ${health.activeModel ?: "未配置"}"
                } else {
                    testResultLabel.text = "❌ 连接失败，请检查后端是否已启动"
                }
            }
        }.start()
    }

    // ==================== 属性 ====================

    var backendUrl: String
        get() = backendUrlField.text
        set(value) { backendUrlField.text = value }

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
}
