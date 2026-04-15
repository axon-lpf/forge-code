package com.codeforge.plugin.idea.statusbar

import com.codeforge.plugin.idea.service.LlmService
import com.codeforge.plugin.idea.toolwindow.ModelChangedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

// ==================== 工厂 ====================

/**
 * 状态栏 Widget 工厂，在 plugin.xml 中注册
 */
class ModelStatusBarFactory : StatusBarWidgetFactory {
    override fun getId(): String = ModelStatusBarWidget.ID
    override fun getDisplayName(): String = "CodeForge 模型"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = ModelStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

// ==================== Widget ====================

/**
 * 底部状态栏模型切换 Widget
 *
 * 显示当前激活的模型名称，点击后弹出模型选择列表。
 */
class ModelStatusBarWidget(project: Project) :
    EditorBasedWidget(project),
    StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "CodeForge.ModelStatusBar"
    }

    private val log = logger<ModelStatusBarWidget>()
    private val llmService = LlmService.getInstance()

    /** 当前显示的文字 */
    private var displayText: String = "⚡ CodeForge"
    private var activeProvider: String = ""
    private var activeModel: String = ""

    override fun ID(): String = ID

    override fun getText(): String = displayText

    override fun getTooltipText(): String = "点击切换 AI 模型 — CodeForge"

    override fun getAlignment(): Float = 0f

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        showModelPicker(event)
    }

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        // 订阅模型切换事件（由 CodeForgeChatPanel 发布）
        project.messageBus.connect(this)
            .subscribe(ModelChangedListener.TOPIC, ModelChangedListener { provider, model ->
                updateDisplay(provider, model)
            })
        // 初始化时异步加载当前模型
        refreshCurrentModel()
    }

    /**
     * 获取当前激活模型并刷新显示
     */
    fun refreshCurrentModel() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val activeInfo = llmService.getActiveInfo()
            val provider = activeInfo.provider ?: ""
            val model = activeInfo.model ?: ""
            ApplicationManager.getApplication().invokeLater {
                updateDisplay(provider, model)
            }
        }
    }

    private fun updateDisplay(provider: String, model: String) {
        activeProvider = provider
        activeModel = model
        displayText = if (model.isNotEmpty()) "⚡ $model" else "⚡ CodeForge"
        myStatusBar?.updateWidget(ID)
    }

    // ==================== 模型选择弹窗 ====================

    /**
     * 弹出模型选择列表
     */
    private fun showModelPicker(event: MouseEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val providerList = llmService.getProviderList()

            // 构建分组的展示项
            val items = mutableListOf<ModelItem>()

            val cnProviders = providerList.filter { it.region == "cn" && it.hasApiKey }
            val globalProviders = providerList.filter { it.region == "global" && it.hasApiKey }
            val unconfigured = providerList.filter { !it.hasApiKey }

            if (cnProviders.isNotEmpty()) {
                items.add(ModelItem.Header("🇨🇳 国内模型"))
                cnProviders.forEach { p ->
                    items.add(ModelItem.Model(p.name, p.currentModel ?: p.models.firstOrNull() ?: "", p.displayName))
                }
            }
            if (globalProviders.isNotEmpty()) {
                items.add(ModelItem.Header("🌍 国外模型"))
                globalProviders.forEach { p ->
                    items.add(ModelItem.Model(p.name, p.currentModel ?: p.models.firstOrNull() ?: "", p.displayName))
                }
            }
            if (unconfigured.isNotEmpty()) {
                items.add(ModelItem.Header("⚙️ 未配置（需填写 API Key）"))
                unconfigured.forEach { p ->
                    items.add(ModelItem.Model(p.name, "", p.displayName, disabled = true))
                }
            }

            ApplicationManager.getApplication().invokeLater {
                val popup = buildPopup(items)
                popup.showInCenterOf(event.component)
            }
        }
    }

    private fun buildPopup(items: List<ModelItem>): ListPopup {
        return JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<ModelItem>("选择模型 — CodeForge", items) {
                override fun getTextFor(value: ModelItem): String = when (value) {
                    is ModelItem.Header -> value.title
                    is ModelItem.Model -> {
                        val active = value.provider == activeProvider
                        val prefix = if (active) "✓ " else "   "
                        val suffix = if (value.disabled) " (未配置)" else ""
                        "$prefix${value.displayName} · ${value.model}$suffix"
                    }
                }

                override fun isSelectable(value: ModelItem): Boolean =
                    value is ModelItem.Model && !value.disabled

                override fun onChosen(selectedValue: ModelItem, finalChoice: Boolean): PopupStep<*>? {
                    if (selectedValue is ModelItem.Model) {
                        doSwitchModel(selectedValue.provider, selectedValue.model)
                    }
                    return PopupStep.FINAL_CHOICE
                }

                override fun isMnemonicsNavigationEnabled(): Boolean = false
            }
        )
    }

    private fun doSwitchModel(provider: String, model: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val success = llmService.switchModel(provider, model)
            if (success) {
                val activeInfo = llmService.getActiveInfo()
                ApplicationManager.getApplication().invokeLater {
                    updateDisplay(activeInfo.provider ?: provider, activeInfo.model ?: model)
                }
            }
        }
    }
}

// ==================== 数据模型 ====================

sealed class ModelItem {
    data class Header(val title: String) : ModelItem()
    data class Model(
        val provider: String,
        val model: String,
        val displayName: String,
        val disabled: Boolean = false
    ) : ModelItem()
}

