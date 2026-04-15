package com.codeforge.plugin.idea.agent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P2-9 · T25 — Checkpoint / 修改回滚管理器
 *
 * 在 Agent 任务开始前自动快照即将被修改的文件，
 * 用户可随时一键回滚到任意历史快照。
 *
 * 功能：
 *  - createCheckpoint：在 Agent 任务开始前，读取 *已存在* 文件的原始内容并保存为快照
 *  - restore：将快照中的所有文件内容批量写回磁盘（物理写回）
 *  - getAll：获取全部 Checkpoint 列表（按时间倒序）
 *  - clear：清空所有 Checkpoint
 *
 * 限制：最多保留 10 个 Checkpoint（超出时 FIFO 删除最旧的）
 */
object CheckpointManager {

    private val log = logger<CheckpointManager>()

    /** 单个文件快照 */
    data class FileSnapshot(
        val relativePath: String,
        val content: String,       // 文件原始内容（UTF-8）
        val existed: Boolean       // true = 原来存在；false = 原来不存在（Agent 新建，回滚时需删除）
    )

    /** Checkpoint 数据类 */
    data class Checkpoint(
        val id: String,
        val timestamp: Long,
        val taskDescription: String,
        val snapshots: List<FileSnapshot>
    ) {
        /** 格式化时间供 UI 展示 */
        val timeLabel: String
            get() = SimpleDateFormat("HH:mm:ss").format(Date(timestamp))

        /** 日期标签 */
        val dateLabel: String
            get() = SimpleDateFormat("yyyy-MM-dd").format(Date(timestamp))
    }

    private const val MAX_CHECKPOINTS = 10

    /** 内存中维护的 Checkpoint 列表（最新在前） */
    private val checkpoints = CopyOnWriteArrayList<Checkpoint>()

    // ==================== 公开 API ====================

    /**
     * 创建 Checkpoint — 在 Agent 任务开始前调用。
     *
     * 扫描即将被修改的文件路径列表（write_file 会用到的路径），
     * 读取每个文件的当前内容并保存为快照。
     *
     * 注意：此时 Agent 尚未开始执行，文件内容就是"修改前"的原始状态。
     * 由于无法在任务开始前预知 Agent 会写哪些文件，这里保存整个项目已有文件
     * 的轻量级元信息索引（路径 + 内容），仅在 Agent 实际写文件时（writeFile 被调用）
     * 才补充记录修改前快照（incremental snapshot 策略）。
     *
     * @param project         当前项目
     * @param taskDescription 本次 Agent 任务的用户输入描述（用于 UI 展示）
     * @return 创建的 Checkpoint id
     */
    fun createCheckpoint(project: Project, taskDescription: String): String {
        val id = UUID.randomUUID().toString().take(8)
        val cp = Checkpoint(
            id = id,
            timestamp = System.currentTimeMillis(),
            taskDescription = taskDescription.take(80).replace("\n", " "),
            snapshots = emptyList()   // 初始为空，由 recordSnapshot 动态追加
        )
        checkpoints.add(0, cp)  // 最新在前

        // FIFO：超出限制时移除最旧的
        while (checkpoints.size > MAX_CHECKPOINTS) {
            val removed = checkpoints.removeAt(checkpoints.size - 1)
            log.info("CheckpointManager: FIFO 删除旧 checkpoint [${removed.id}]")
        }

        log.info("CheckpointManager: 创建 checkpoint [$id] — ${cp.taskDescription}")
        return id
    }

    /**
     * 记录单个文件的修改前快照（在 write_file 工具实际写入前调用）。
     *
     * 若该文件在此 Checkpoint 中已有记录，则跳过（保留最早的"原始"内容）。
     *
     * @param checkpointId  Checkpoint id
     * @param relativePath  文件相对项目根目录的路径
     * @param originalContent 文件修改前的内容（文件不存在时传 null）
     */
    fun recordSnapshot(checkpointId: String, relativePath: String, originalContent: String?) {
        val idx = checkpoints.indexOfFirst { it.id == checkpointId }
        if (idx < 0) return  // Checkpoint 不存在（可能已被清理）

        val cp = checkpoints[idx]
        // 已记录过该路径则跳过
        if (cp.snapshots.any { it.relativePath == relativePath }) return

        val snapshot = FileSnapshot(
            relativePath = relativePath,
            content = originalContent ?: "",
            existed = (originalContent != null)
        )
        // CopyOnWriteArrayList 元素不可变，需替换整个 Checkpoint
        val updated = cp.copy(snapshots = cp.snapshots + snapshot)
        checkpoints[idx] = updated
        log.info("CheckpointManager: 记录快照 [$checkpointId] → $relativePath（existed=${snapshot.existed}）")
    }

    /**
     * 回滚到指定 Checkpoint — 将所有快照文件内容批量写回磁盘。
     *
     * - 原来存在的文件：恢复为快照中的内容
     * - 原来不存在的文件（Agent 新建）：删除该文件
     *
     * @param project       当前项目
     * @param checkpointId  要回滚到的 Checkpoint id
     * @return true = 回滚成功，false = 失败
     */
    fun restore(project: Project, checkpointId: String): Boolean {
        val cp = checkpoints.find { it.id == checkpointId }
            ?: return false.also { log.warn("CheckpointManager: 找不到 checkpoint [$checkpointId]") }

        if (cp.snapshots.isEmpty()) {
            log.info("CheckpointManager: checkpoint [$checkpointId] 无文件快照，无需回滚")
            return true
        }

        val rootPath = project.basePath
            ?: return false.also { log.warn("CheckpointManager: 找不到项目根目录") }

        var successCount = 0
        var failCount = 0

        cp.snapshots.forEach { snapshot ->
            try {
                val file = File(rootPath, snapshot.relativePath)
                if (snapshot.existed) {
                    // 恢复原始内容
                    file.parentFile?.mkdirs()
                    file.writeText(snapshot.content, Charsets.UTF_8)
                    log.info("CheckpointManager: 恢复文件 [${snapshot.relativePath}]")
                } else {
                    // Agent 新建的文件，回滚时删除
                    if (file.exists()) {
                        file.delete()
                        log.info("CheckpointManager: 删除 Agent 新建文件 [${snapshot.relativePath}]")
                    }
                }
                successCount++
            } catch (e: Exception) {
                failCount++
                log.error("CheckpointManager: 恢复文件 [${snapshot.relativePath}] 失败", e)
            }
        }

        // 刷新 VFS，让 IDE 感知文件变化
        try {
            val rootDir = File(rootPath)
            LocalFileSystem.getInstance().refreshIoFiles(
                cp.snapshots.map { File(rootPath, it.relativePath) },
                true, false, null
            )
        } catch (e: Exception) {
            log.warn("CheckpointManager: VFS 刷新失败（不影响文件内容恢复）: ${e.message}")
        }

        log.info("CheckpointManager: 回滚完成 [$checkpointId]，成功=$successCount，失败=$failCount")
        return failCount == 0
    }

    /**
     * 获取全部 Checkpoint（按时间倒序，最新在前）
     */
    fun getAll(): List<Checkpoint> = checkpoints.toList()

    /**
     * 获取指定 Checkpoint 的概要信息（用于 JS 序列化）
     */
    fun getSummaryList(): List<Map<String, Any>> {
        return checkpoints.map { cp ->
            mapOf(
                "id" to cp.id,
                "timestamp" to cp.timestamp,
                "timeLabel" to cp.timeLabel,
                "dateLabel" to cp.dateLabel,
                "taskDescription" to cp.taskDescription,
                "fileCount" to cp.snapshots.size
            )
        }
    }

    /**
     * 清空所有 Checkpoint
     */
    fun clear() {
        val count = checkpoints.size
        checkpoints.clear()
        log.info("CheckpointManager: 清空所有 checkpoint（共 $count 个）")
    }

    /**
     * 删除指定 Checkpoint
     */
    fun remove(checkpointId: String) {
        val removed = checkpoints.removeIf { it.id == checkpointId }
        if (removed) log.info("CheckpointManager: 删除 checkpoint [$checkpointId]")
    }
}
