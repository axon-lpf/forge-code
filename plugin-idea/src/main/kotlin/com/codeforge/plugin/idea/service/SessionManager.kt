package com.codeforge.plugin.idea.service

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * 会话管理器 — 负责多会话的创建、持久化、读取和删除
 *
 * 存储位置：~/.config/JetBrains/[IDE]/codeforge/sessions/
 * - sessions-index.json   会话索引（轻量，列表展示用）
 * - {sessionId}.json      单个会话完整内容
 */
object SessionManager {

    private val log = logger<SessionManager>()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** 存储根目录 */
    private val sessionsDir: File by lazy {
        File(PathManager.getConfigPath(), "codeforge/sessions").also { it.mkdirs() }
    }

    private val indexFile: File get() = File(sessionsDir, "sessions-index.json")

    // ==================== 数据类 ====================

    /** 会话摘要（索引中存储，列表展示用） */
    data class SessionSummary(
        val id: String,
        var title: String,
        val createdAt: Long,        // epoch millis
        var updatedAt: Long,
        var model: String = "",
        var provider: String = "",
        var messageCount: Int = 0
    )

    /** 完整会话（含消息列表） */
    data class Session(
        val id: String,
        var title: String,
        val createdAt: Long,
        var updatedAt: Long,
        var model: String = "",
        var provider: String = "",
        val messages: MutableList<Message> = mutableListOf()
    )

    /** 单条消息 */
    data class Message(
        val role: String,           // "user" / "assistant"
        val content: String,
        val timestamp: Long = Instant.now().toEpochMilli()
    )

    // ==================== 公开 API ====================

    /** 创建新会话，返回会话 ID */
    fun createSession(provider: String = "", model: String = ""): Session {
        val now = Instant.now().toEpochMilli()
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            createdAt = now,
            updatedAt = now,
            provider = provider,
            model = model
        )
        saveSession(session)
        addToIndex(session)
        return session
    }

    /** 加载会话列表（按更新时间倒序） */
    fun listSessions(): List<SessionSummary> {
        return try {
            if (!indexFile.exists()) return emptyList()
            val type = object : TypeToken<MutableList<SessionSummary>>() {}.type
            val list: MutableList<SessionSummary> = gson.fromJson(indexFile.readText(), type) ?: mutableListOf()
            list.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            log.warn("读取会话索引失败", e)
            emptyList()
        }
    }

    /** 加载单个会话完整内容 */
    fun loadSession(sessionId: String): Session? {
        return try {
            val file = sessionFile(sessionId)
            if (!file.exists()) return null
            gson.fromJson(file.readText(), Session::class.java)
        } catch (e: Exception) {
            log.warn("读取会话失败: $sessionId", e)
            null
        }
    }

    /** 向会话追加一条消息，并持久化 */
    fun appendMessage(sessionId: String, role: String, content: String): Session? {
        val session = loadSession(sessionId) ?: return null
        session.messages.add(Message(role = role, content = content))
        session.updatedAt = Instant.now().toEpochMilli()

        // 首条用户消息自动设为标题
        if (session.title == "新会话" && role == "user") {
            session.title = content.take(30).replace("\n", " ")
        }

        saveSession(session)
        updateIndex(session)
        return session
    }

    /** 更新会话使用的模型 */
    fun updateSessionModel(sessionId: String, provider: String, model: String) {
        val session = loadSession(sessionId) ?: return
        session.provider = provider
        session.model = model
        session.updatedAt = Instant.now().toEpochMilli()
        saveSession(session)
        updateIndex(session)
    }

    /** 重命名会话 */
    fun renameSession(sessionId: String, newTitle: String) {
        val session = loadSession(sessionId) ?: return
        session.title = newTitle
        saveSession(session)
        updateIndex(session)
    }

    /** 删除会话 */
    fun deleteSession(sessionId: String) {
        sessionFile(sessionId).delete()
        removeFromIndex(sessionId)
    }

    /** 清空会话消息（保留会话元数据） */
    fun clearSession(sessionId: String) {
        val session = loadSession(sessionId) ?: return
        session.messages.clear()
        session.title = "新会话"
        session.updatedAt = Instant.now().toEpochMilli()
        saveSession(session)
        updateIndex(session)
    }

    /** 获取会话的消息历史（用于发送给 LLM） */
    fun getMessages(sessionId: String): List<Map<String, Any>> {
        val session = loadSession(sessionId) ?: return emptyList()
        return session.messages.map { mapOf("role" to it.role, "content" to it.content) }
    }

    // ==================== 私有方法 ====================

    private fun sessionFile(sessionId: String) = File(sessionsDir, "$sessionId.json")

    private fun saveSession(session: Session) {
        try {
            sessionFile(session.id).writeText(gson.toJson(session))
        } catch (e: Exception) {
            log.error("保存会话失败: ${session.id}", e)
        }
    }

    private fun addToIndex(session: Session) {
        val index = loadIndex().toMutableList()
        index.add(0, toSummary(session))
        saveIndex(index)
    }

    private fun updateIndex(session: Session) {
        val index = loadIndex().toMutableList()
        val i = index.indexOfFirst { it.id == session.id }
        if (i >= 0) index[i] = toSummary(session)
        else index.add(0, toSummary(session))
        saveIndex(index)
    }

    private fun removeFromIndex(sessionId: String) {
        val index = loadIndex().toMutableList()
        index.removeAll { it.id == sessionId }
        saveIndex(index)
    }

    private fun loadIndex(): List<SessionSummary> {
        return try {
            if (!indexFile.exists()) return emptyList()
            val type = object : TypeToken<MutableList<SessionSummary>>() {}.type
            gson.fromJson(indexFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(index: List<SessionSummary>) {
        try {
            indexFile.writeText(gson.toJson(index))
        } catch (e: Exception) {
            log.error("保存会话索引失败", e)
        }
    }

    private fun toSummary(session: Session) = SessionSummary(
        id = session.id,
        title = session.title,
        createdAt = session.createdAt,
        updatedAt = session.updatedAt,
        model = session.model,
        provider = session.provider,
        messageCount = session.messages.size
    )
}

