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
        private const val SYSTEM_PROMPT = """You are an expert code completion engine. Rules:
1. Output ONLY the code that should replace <FILL>, nothing else.
2. Do NOT wrap in markdown code blocks (no ``` markers).
3. Do NOT add explanations or comments about what you generated.
4. Match the existing code style (indentation, naming conventions).
5. Generate 1-5 lines of natural continuation. Stop at a logical breakpoint.
6. If unsure, output nothing."""
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

        val fileName = request.file?.name ?: ""
        val prompt = buildFimPrompt(prefix, suffix, fileName)
        log.debug("触发行内补全，文件=$fileName，前缀长度=${prefix.length}")

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
                }
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
        return "${hint}补全 <FILL> 处的代码（只输出补全内容）:\n\n$prefix<FILL>$suffix"
    }

    private fun postProcess(raw: String): String =
        raw.trim()
            .removePrefix("```").removeSuffix("```").trim()
            .lines().take(10).joinToString("\n")
}

