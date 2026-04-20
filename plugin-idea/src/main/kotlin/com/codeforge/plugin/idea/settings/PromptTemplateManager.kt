package com.codeforge.plugin.idea.settings

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger

/**
 * B6：自定义 Prompt 模板管理器
 *
 * 内置模板 + 用户自定义模板，CRUD 操作，持久化到 ForgeSettings。
 */
object PromptTemplateManager {

    private val log = logger<PromptTemplateManager>()
    private val gson = Gson()

    data class PromptTemplate(
        val id: String,
        val name: String,
        val icon: String,
        val prompt: String,
        val isBuiltin: Boolean = false
    )

    /** 内置模板列表 */
    val BUILTIN_TEMPLATES = listOf(
        PromptTemplate(
            id = "explain",
            name = "解释代码",
            icon = "📖",
            prompt = "请详细解释以下代码的功能、逻辑和设计意图：\n\n{code}\n\n请用中文回答，解释要清晰易懂。",
            isBuiltin = true
        ),
        PromptTemplate(
            id = "optimize",
            name = "优化性能",
            icon = "⚡",
            prompt = "请优化以下代码的性能，重点关注算法复杂度、内存使用和可读性：\n\n{code}\n\n请直接输出优化后的完整代码，不要解释。",
            isBuiltin = true
        ),
        PromptTemplate(
            id = "refactor",
            name = "重构代码",
            icon = "🔧",
            prompt = "请对以下代码进行重构，提升代码质量（可读性、可维护性、SOLID 原则等）：\n\n{code}\n\n请直接输出重构后的完整代码。",
            isBuiltin = true
        ),
        PromptTemplate(
            id = "test",
            name = "生成测试",
            icon = "🧪",
            prompt = "请为以下代码生成单元测试（JUnit 格式，Java/Kotlin）：\n\n{code}\n\n请直接输出完整的测试代码文件。",
            isBuiltin = true
        ),
        PromptTemplate(
            id = "doc",
            name = "生成文档",
            icon = "📝",
            prompt = "请为以下代码生成详细的 Javadoc / KDoc 文档注释：\n\n{code}\n\n请直接输出带注释的完整代码。",
            isBuiltin = true
        ),
        PromptTemplate(
            id = "bug",
            name = "查找 Bug",
            icon = "🐛",
            prompt = "请分析以下代码，找出潜在的 Bug、性能问题和安全漏洞：\n\n{code}\n\n请列出具体问题和修复建议。",
            isBuiltin = true
        ),
        PromptTemplate(
            id = "security",
            name = "安全审查",
            icon = "🔒",
            prompt = "请对以下代码进行安全审查，重点检查：SQL注入、XSS、密码存储、权限校验等：\n\n{code}\n\n请列出发现的安全问题及修复方案。",
            isBuiltin = true
        ),
        PromptTemplate(
            id = "translate",
            name = "翻译注释",
            icon = "🌐",
            prompt = "请将以下代码的注释翻译成中文，保持注释格式不变：\n\n{code}\n\n请直接输出翻译后的完整代码。",
            isBuiltin = true
        )
    )

    /**
     * 获取所有模板（内置 + 自定义）
     */
    fun getAllTemplates(): List<PromptTemplate> {
        val custom = loadCustomTemplates()
        return BUILTIN_TEMPLATES + custom
    }

    /**
     * 获取内置模板
     */
    fun getBuiltinTemplates(): List<PromptTemplate> = BUILTIN_TEMPLATES

    /**
     * 获取自定义模板
     */
    fun getCustomTemplates(): List<PromptTemplate> = loadCustomTemplates()

    /**
     * 新增自定义模板
     */
    fun addCustomTemplate(template: PromptTemplate) {
        val all = loadCustomTemplates().toMutableList()
        all.removeAll { it.id == template.id }  // 移除同ID
        all.add(template.copy(isBuiltin = false))
        saveCustomTemplates(all)
        log.info("PromptTemplateManager: 添加自定义模板 ${template.id}")
    }

    /**
     * 更新自定义模板
     */
    fun updateCustomTemplate(template: PromptTemplate) {
        val all = loadCustomTemplates().toMutableList()
        val idx = all.indexOfFirst { it.id == template.id }
        if (idx >= 0) {
            all[idx] = template.copy(isBuiltin = false)
            saveCustomTemplates(all)
        }
    }

    /**
     * 删除自定义模板
     */
    fun deleteCustomTemplate(id: String) {
        val all = loadCustomTemplates().toMutableList()
        all.removeAll { it.id == id }
        saveCustomTemplates(all)
        log.info("PromptTemplateManager: 删除自定义模板 $id")
    }

    // ==================== 私有方法 ====================

    private fun loadCustomTemplates(): List<PromptTemplate> {
        return try {
            val json = CodeForgeSettings.getInstance().promptTemplates
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<PromptTemplate>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustomTemplates(templates: List<PromptTemplate>) {
        CodeForgeSettings.getInstance().promptTemplates = gson.toJson(templates)
    }
}
