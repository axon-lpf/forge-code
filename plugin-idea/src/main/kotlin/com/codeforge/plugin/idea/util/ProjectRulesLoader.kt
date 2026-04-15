package com.codeforge.plugin.idea.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T14：项目规则文件加载器
 *
 * 按优先级查找规则文件：
 * 1. 项目根目录 `.codeforge.md`
 * 2. 用户家目录 `~/.codeforge/global.md`
 *
 * 支持文件变更自动重载，内容限制 4000 字符。
 */
object ProjectRulesLoader {

    private val log = logger<ProjectRulesLoader>()

    /** 最大内容长度（字符） */
    private const val MAX_CONTENT_LENGTH = 4000

    /** 项目规则文件名 */
    private const val PROJECT_RULES_FILE = ".codeforge.md"

    /** 全局规则文件路径（相对于用户家目录） */
    private const val GLOBAL_RULES_PATH = ".codeforge/global.md"

    /** 规则内容缓存：projectPath → (rulesContent, sourceFilePath) */
    private val cache = ConcurrentHashMap<String, Pair<String, String>>()

    /** 已注册监听器的项目集合，避免重复注册 */
    private val watchedProjects = mutableSetOf<String>()

    /** 全局 VFS 文件变更监听器 */
    private var vfsListener: VirtualFileListener? = null

    /**
     * 加载指定项目的规则内容字符串（供 AgentService / LlmService 注入 system prompt）
     *
     * @param project 当前项目
     * @return 规则内容，若无规则文件则返回空字符串
     */
    fun load(project: Project): String {
        return loadInfo(project)?.first ?: ""
    }

    /**
     * 加载指定项目的规则完整信息
     *
     * @param project 当前项目
     * @return Pair<规则内容, 规则文件来源路径>，若无规则文件则返回 null
     */
    fun loadInfo(project: Project): Pair<String, String>? {
        val projectPath = project.basePath ?: return null

        // 确保已注册全局 VFS 监听器
        ensureVfsListener(project)

        // 返回缓存（若有效）
        cache[projectPath]?.let { return it }

        // 查找并读取规则文件
        return findAndLoad(projectPath).also { result ->
            if (result != null) {
                cache[projectPath] = result
                log.info("ProjectRulesLoader: 已加载规则文件 ${result.second}，内容长度 ${result.first.length}")
            } else {
                log.debug("ProjectRulesLoader: 未找到规则文件（项目: $projectPath）")
            }
        }
    }

    /**
     * 强制重新加载（清除缓存）
     *
     * @param projectPath 项目根路径
     */
    fun invalidate(projectPath: String) {
        cache.remove(projectPath)
        log.info("ProjectRulesLoader: 已清除规则缓存（项目: $projectPath）")
    }

    /**
     * 按优先级查找规则文件并读取内容
     */
    private fun findAndLoad(projectPath: String): Pair<String, String>? {
        // 优先级1：项目根目录 .codeforge.md
        val projectRules = File(projectPath, PROJECT_RULES_FILE)
        if (projectRules.exists() && projectRules.isFile) {
            val content = readAndTruncate(projectRules)
            if (content.isNotBlank()) {
                return Pair(content, projectRules.absolutePath)
            }
        }

        // 优先级2：用户家目录 ~/.codeforge/global.md
        val homeDir = System.getProperty("user.home") ?: return null
        val globalRules = File(homeDir, GLOBAL_RULES_PATH)
        if (globalRules.exists() && globalRules.isFile) {
            val content = readAndTruncate(globalRules)
            if (content.isNotBlank()) {
                return Pair(content, globalRules.absolutePath)
            }
        }

        return null
    }

    /**
     * 读取文件并截断到最大长度
     */
    private fun readAndTruncate(file: File): String {
        return try {
            val content = file.readText(Charsets.UTF_8)
            if (content.length > MAX_CONTENT_LENGTH) {
                log.warn("ProjectRulesLoader: 规则文件内容超过 $MAX_CONTENT_LENGTH 字符，已截断（${file.absolutePath}）")
                content.take(MAX_CONTENT_LENGTH) + "\n\n*[规则内容过长，已截断至 $MAX_CONTENT_LENGTH 字符]*"
            } else {
                content
            }
        } catch (e: Exception) {
            log.error("ProjectRulesLoader: 读取规则文件失败（${file.absolutePath}）", e)
            ""
        }
    }

    /**
     * 注册 VirtualFileListener 监听规则文件变更，自动清除缓存
     * 同一个项目只注册一次监听器
     */
    private fun ensureVfsListener(project: Project) {
        val projectPath = project.basePath ?: return
        if (watchedProjects.contains(projectPath)) return

        synchronized(watchedProjects) {
            if (watchedProjects.contains(projectPath)) return

            // 若全局监听器尚未注册，则注册一个
            if (vfsListener == null) {
                val listener = object : VirtualFileListener {
                    override fun contentsChanged(event: VirtualFileEvent) {
                        onFileChanged(event.file.path)
                    }

                    override fun fileCreated(event: VirtualFileEvent) {
                        onFileChanged(event.file.path)
                    }

                    override fun fileDeleted(event: VirtualFileEvent) {
                        onFileChanged(event.file.path)
                    }
                }
                VirtualFileManager.getInstance().addVirtualFileListener(listener)
                vfsListener = listener
                log.info("ProjectRulesLoader: 已注册全局 VFS 文件变更监听器")
            }

            watchedProjects.add(projectPath)
            log.debug("ProjectRulesLoader: 开始监听项目规则文件变更（$projectPath）")
        }
    }

    /**
     * 文件变更回调：若变更的是规则文件，则清除对应项目的缓存
     */
    private fun onFileChanged(filePath: String) {
        val normalizedPath = filePath.replace('\\', '/')
        val fileName = File(filePath).name

        // 检查是否是 .codeforge.md（项目级规则文件）
        if (fileName == PROJECT_RULES_FILE) {
            // 找到对应项目并清除缓存
            val projectPath = watchedProjects.firstOrNull { projPath ->
                normalizedPath.startsWith(projPath.replace('\\', '/'))
            }
            if (projectPath != null) {
                invalidate(projectPath)
                log.info("ProjectRulesLoader: 规则文件已变更，自动重载（$filePath）")
            }
        }

        // 检查是否是全局规则文件 ~/.codeforge/global.md
        val homeDir = System.getProperty("user.home") ?: return
        val globalRulesPath = File(homeDir, GLOBAL_RULES_PATH).absolutePath
            .replace('\\', '/')
        if (normalizedPath == globalRulesPath) {
            // 全局规则变更，清除所有项目缓存
            cache.clear()
            log.info("ProjectRulesLoader: 全局规则文件已变更，清除所有项目缓存")
        }
    }

    /**
     * 获取友好的规则文件来源显示名称
     * 例："/home/user/.codeforge/global.md" → "~/.codeforge/global.md（全局）"
     *     "/project/.codeforge.md" → ".codeforge.md（项目）"
     */
    fun getSourceDisplayName(sourcePath: String): String {
        val homeDir = System.getProperty("user.home") ?: ""
        return when {
            sourcePath.contains(".codeforge/global.md") ->
                "~/.codeforge/global.md（全局规则）"
            sourcePath.endsWith(PROJECT_RULES_FILE) ->
                "$PROJECT_RULES_FILE（项目规则）"
            else -> sourcePath
        }
    }
}
