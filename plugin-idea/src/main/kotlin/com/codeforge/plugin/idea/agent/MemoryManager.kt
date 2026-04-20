package com.codeforge.plugin.idea.agent

import com.codeforge.plugin.idea.settings.CodeForgeSettings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.logger
import java.time.Instant

/**
 * B2：Memory 工具 — 跨会话持久化记忆
 *
 * AI 可记忆用户偏好、项目约定、历史决策，按 projectPath 隔离。
 * 最多保留 100 条，超出时删除最旧的。
 */
object MemoryManager {

    private val log = logger<MemoryManager>()
    private val gson = Gson()
    private const val MAX_ENTRIES = 100

    data class MemoryEntry(
        val key: String,
        val content: String,
        val timestamp: Long,
        val projectPath: String
    )

    /**
     * 保存一条记忆
     */
    fun save(key: String, content: String, projectPath: String) {
        val entries = loadAll().toMutableList()

        // 移除同项目同 key 的旧记录
        entries.removeAll { it.projectPath == projectPath && it.key == key }

        // 添加新记录（放在最前面）
        entries.add(0, MemoryEntry(key, content, Instant.now().toEpochMilli(), projectPath))

        // 超出上限时删除最旧的
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }

        persist(entries)
        log.info("MemoryManager: 保存记忆 key=$key, projectPath=$projectPath")
    }

    /**
     * 读取指定记忆
     */
    fun read(key: String, projectPath: String): String? {
        return loadAll().find { it.projectPath == projectPath && it.key == key }?.content
    }

    /**
     * 列出指定项目的所有记忆
     */
    fun listAll(projectPath: String): List<MemoryEntry> {
        return loadAll().filter { it.projectPath == projectPath }
    }

    /**
     * 删除指定记忆
     */
    fun delete(key: String, projectPath: String) {
        val entries = loadAll().toMutableList()
        entries.removeAll { it.projectPath == projectPath && it.key == key }
        persist(entries)
        log.info("MemoryManager: 删除记忆 key=$key, projectPath=$projectPath")
    }

    /**
     * 格式化记忆为文本，注入 Agent system prompt
     */
    fun formatAsPrompt(projectPath: String): String {
        val entries = listAll(projectPath)
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("== 记忆 ==")
            for (entry in entries) {
                appendLine("- ${entry.key}: ${entry.content}")
            }
        }
    }

    // ==================== 私有方法 ====================

    private fun loadAll(): List<MemoryEntry> {
        return try {
            val json = CodeForgeSettings.getInstance().memoryData
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<MemoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(entries: List<MemoryEntry>) {
        CodeForgeSettings.getInstance().memoryData = gson.toJson(entries)
    }
}
