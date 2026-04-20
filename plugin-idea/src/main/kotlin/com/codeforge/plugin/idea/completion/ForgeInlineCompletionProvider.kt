package com.codeforge.plugin.idea.completion

import com.codeforge.plugin.idea.service.LlmService
import com.codeforge.plugin.idea.settings.CodeForgeSettings
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CodeForge 行内代码补全 Provider
 * 兼容 IntelliJ 2024.1（API: InlineCompletionSuggestion.withFlow / .empty()）
 */
class CodeForgeInlineCompletionProvider : InlineCompletionProvider {

    private val log = logger<CodeForgeInlineCompletionProvider>()

    companion object {
        /** 补全系统提示词 */
        private const val SYSTEM_PROMPT = """You are an expert code completion engine. Output ONLY the completion code, no markdown, no explanations."""

        /** 语言对应的注释模式（跳过注释行触发） */
        private val COMMENT_PATTERNS = mapOf(
            "java" to setOf("//", "/*", "*/"),
            "kt" to setOf("//", "/*", "*/"),
            "js" to setOf("//", "/*"),
            "ts" to setOf("//", "/*"),
            "py" to setOf("#", "\"\"\""),
            "go" to setOf("//", "/*"),
            "rs" to setOf("//", "/*"),
            "cpp" to setOf("//", "/*"),
            "c" to setOf("//", "/*"),
            "swift" to setOf("//", "/*"),
            "sql" to setOf("--", "/*")
        )

        /** 语言对应的 import/package 行模式 */
        private val IMPORT_PATTERNS = mapOf(
            "java" to setOf("import ", "package "),
            "kt" to setOf("import ", "package "),
            "js" to setOf("import ", "const ", "let ", "function "),
            "ts" to setOf("import ", "const ", "let ", "function "),
            "py" to setOf("import ", "from "),
            "go" to setOf("import ", "package "),
            "rs" to setOf("use ", "mod "),
            "cpp" to setOf("#include", "using "),
            "c" to setOf("#include", "using "),
            "swift" to setOf("import ", "func "),
            "sql" to setOf("SELECT", "INSERT", "UPDATE", "CREATE")
        )
    }

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("com.codeforge.inline")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!CodeForgeSettings.getInstance().inlineCompletionEnabled) return false
        return event is InlineCompletionEvent.DocumentChange
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val settings = CodeForgeSettings.getInstance()
        if (!settings.inlineCompletionEnabled) {
            return InlineCompletionSuggestion.empty()
        }

        val document = request.document
        val offset = request.endOffset
        if (offset <= 0 || offset > document.textLength) {
            return InlineCompletionSuggestion.empty()
        }

        val fullText = document.text
        val prefix = buildPrefix(fullText, offset)
        val suffix = buildSuffix(fullText, offset)
        if (prefix.isBlank()) return InlineCompletionSuggestion.empty()

        // 跳过注释行、空行等不需要补全的场景
        val lastLine = prefix.lines().lastOrNull()?.trim() ?: ""
        if (lastLine.isEmpty()) return InlineCompletionSuggestion.empty()

        // 检测语言
        val fileName = request.file?.name ?: ""
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val lang = ext.ifBlank { "txt" }

        // B7：过滤不触发补全的场景
        val commentPatterns = COMMENT_PATTERNS[lang] ?: emptySet()
        if (commentPatterns.any { lastLine.startsWith(it) }) return InlineCompletionSuggestion.empty()

        val importPatterns = IMPORT_PATTERNS[lang] ?: emptySet()
        if (importPatterns.any { lastLine.startsWith(it) }) return InlineCompletionSuggestion.empty()

        // B7：检查最后一行是否是纯字符串/注释内容（字符串内不触发）
        if (lastLine.startsWith("\"") || lastLine.startsWith("'")) return InlineCompletionSuggestion.empty()

        // B7：FIM 格式 Prompt
        val prompt = buildFimPrompt(prefix, suffix, fileName)
        log.debug("触发行内补全，文件=$fileName，语言=$lang，前缀长度=${prefix.length}")

        // B7：检查缓存
        val cacheKey = prefix.take(100).hashCode().toString()
        CompletionCache.get(prefix)?.let { cached ->
            log.debug("B7: 补全缓存命中，key=$cacheKey")
            return InlineCompletionSuggestion.empty() // 缓存命中时跳过本次请求（避免重复）
        }

        return InlineCompletionSuggestion.withFlow {
            // 防抖延迟：等待用户停止输入
            val delayMs = settings.inlineCompletionDelayMs.toLong().coerceIn(200, 2000)
            kotlinx.coroutines.delay(delayMs)

            val llmService = LlmService.getInstance()
            val resultBuffer = StringBuilder()
            val done = AtomicBoolean(false)

            val messages = listOf(
                mapOf<String, Any>(
                    "role" to "system",
                    "content" to SYSTEM_PROMPT
                ),
                mapOf<String, Any>("role" to "user", "content" to prompt)
            )

            llmService.chatStream(
                messages = messages,
                onToken = { token -> resultBuffer.append(token) },
                onDone = { done.set(true) },
                onError = { e ->
                    log.debug("补全请求失败: ${e.message}")
                    done.set(true)
                },
                onUsage = { _, _ -> }
            )

            // 等待 LLM 返回（最多 8 秒）
            var waited = 0
            while (!done.get() && waited < 8000) {
                kotlinx.coroutines.delay(50)
                waited += 50
            }

            val completion = postProcess(resultBuffer.toString())
            if (completion.isNotBlank()) {
                log.debug("补全结果: ${completion.take(80)}...")
                // B7：写入缓存
                CompletionCache.put(prefix, completion)
                emit(InlineCompletionGrayTextElement(completion))
            }
        }
    }

    private fun buildPrefix(text: String, offset: Int): String =
        text.substring(0, offset.coerceAtMost(text.length))
            .lines().takeLast(60).joinToString("\n")

    private fun buildSuffix(text: String, offset: Int): String =
        (if (offset < text.length) text.substring(offset) else "")
            .lines().take(20).joinToString("\n")

    private fun buildFimPrompt(prefix: String, suffix: String, fileName: String): String {
        val hint = if (fileName.isNotBlank()) "文件: $fileName\n" else ""
        return "${hint}<PRE>$prefix<SUF>$suffix<MID>"
    }

    private fun postProcess(raw: String): String =
        raw.trim()
            .removePrefix("```").removeSuffix("```").trim()
            .lines().take(10).joinToString("\n")
}

