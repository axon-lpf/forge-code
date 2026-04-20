package com.codeforge.plugin.idea.util

import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * A3：文档文本提取器
 * 支持 .md/.txt/.log 及其他文本文件的纯文本提取
 * PDF 暂返回提示文本（需要 PDFBox 依赖）
 */
object DocumentExtractor {

    private val log = logger<DocumentExtractor>()
    private const val MAX_CHARS = 8000

    /**
     * 提取文件纯文本内容
     * @param path 文件绝对路径
     * @return 提取的文本内容，超长截断
     */
    fun extract(path: String): ExtractResult {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return ExtractResult(false, "文件不存在或不是有效文件", null)
        }

        return try {
            val ext = path.substringAfterLast('.', "").lowercase()
            val content = when (ext) {
                "pdf" -> extractPdf(path)
                "md", "txt", "log", "java", "kt", "py", "js", "ts", "tsx", "jsx",
                 "xml", "json", "yaml", "yml", "toml", "ini", "cfg", "conf",
                 "sh", "bash", "bat", "cmd", "ps1", "sql", "html", "css", "scss",
                 "go", "rs", "rb", "php", "c", "cpp", "h", "hpp", "swift",
                 "gradle", "kts", "properties" -> extractText(path)
                else -> extractText(path)
            }

            val truncated = if (content.length > MAX_CHARS) {
                content.take(MAX_CHARS) + "\n\n[... 内容已截断，原文 ${content.length} 字符，超出限制 ${MAX_CHARS} 字符]"
            } else {
                content
            }

            ExtractResult(true, null, truncated)
        } catch (e: Exception) {
            log.warn("文档提取失败: $path", e)
            ExtractResult(false, "读取文件失败: ${e.message}", null)
        }
    }

    private fun extractText(path: String): String {
        return Files.readString(java.nio.file.Paths.get(path))
    }

    private fun extractPdf(path: String): String {
        // PDF 暂不支持，返回提示让用户复制文本
        return "[PDF 文件暂不支持自动提取文本内容]\n文件路径: $path\n\n请复制文件内容后粘贴到聊天框，或将内容粘贴到新 .txt 文件后再附加。"
    }

    data class ExtractResult(
        val success: Boolean,
        val error: String?,
        val content: String?
    )
}
